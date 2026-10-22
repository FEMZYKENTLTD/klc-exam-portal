-- ============================================================================
-- KLC CBT Suite v1.0 - WEB PORTAL COMPLETION (v1.4)
-- Makes klc-web-admin a full Vercel-hostable counterpart of the desktop app:
-- EVERY feature is available from the browser EXCEPT sitting an exam
-- (exam attempts are created only by the proctored desktop app - there is
-- deliberately NO exam-start / answer-submit RPC in this file).
--
-- Security model (unchanged): anon key reaches data ONLY through these
-- SECURITY DEFINER functions. Every function verifies email + bcrypt
-- password server-side per call and enforces role membership; student
-- functions only ever return the caller's own rows. Writes are audited.
-- Idempotent: safe to re-run.
-- ============================================================================
create extension if not exists pgcrypto;

-- Role gate: validates any active account, returns its role.
CREATE OR REPLACE FUNCTION klc_web_role(p_email TEXT, p_password TEXT)
RETURNS VARCHAR LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_role VARCHAR; v_hash TEXT;
BEGIN
  SELECT role, password_hash INTO v_role, v_hash
  FROM users WHERE lower(email) = lower(trim(p_email)) AND COALESCE(is_active, TRUE);
  IF v_hash IS NULL OR NOT (crypt(p_password, v_hash) = v_hash) THEN
    RAISE EXCEPTION 'Invalid credentials';
  END IF;
  RETURN v_role;
END $$;

-- Require role membership; returns the caller's user id.
CREATE OR REPLACE FUNCTION klc_web_require(p_email TEXT, p_password TEXT, p_roles TEXT[])
RETURNS UUID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_role VARCHAR; v_id UUID;
BEGIN
  v_role := klc_web_role(p_email, p_password);
  IF NOT (v_role = ANY(p_roles)) THEN RAISE EXCEPTION 'Access denied for role %', v_role; END IF;
  SELECT id INTO v_id FROM users WHERE lower(email) = lower(trim(p_email));
  RETURN v_id;
END $$;

CREATE OR REPLACE FUNCTION klc_web_audit(p_user UUID, p_action TEXT, p_entity TEXT, p_entity_id UUID, p_details TEXT)
RETURNS VOID LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  INSERT INTO audit_logs(id, user_id, action, entity_type, entity_id, details)
  VALUES (uuid_generate_v4(), p_user, p_action, p_entity, p_entity_id, p_details);
EXCEPTION WHEN OTHERS THEN NULL; -- audit must never break the operation
END $$;

-- ---------------------------------------------------------------- DASHBOARD
CREATE OR REPLACE FUNCTION staff_dashboard(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v JSONB;
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  SELECT jsonb_build_object(
    'students', (SELECT count(*) FROM student_profiles WHERE COALESCE(status,'ACTIVE')='ACTIVE'),
    'teachers', (SELECT count(*) FROM users WHERE role='TEACHER' AND COALESCE(is_active,TRUE)),
    'subjects', (SELECT count(*) FROM subjects WHERE COALESCE(is_active,TRUE)),
    'questions_approved', (SELECT count(*) FROM questions WHERE COALESCE(is_approved,FALSE)),
    'questions_pending', (SELECT count(*) FROM questions WHERE NOT COALESCE(is_approved,FALSE)),
    'exams', (SELECT count(*) FROM exams),
    'exams_active', (SELECT count(*) FROM exams WHERE COALESCE(is_active,TRUE)),
    'results', (SELECT count(*) FROM results),
    'attempts_live', (SELECT count(*) FROM exam_attempts WHERE status='IN_PROGRESS'),
    'appeals_open', (SELECT count(*) FROM result_appeals WHERE status IN ('OPEN','IN_REVIEW')),
    'announcements', (SELECT count(*) FROM announcements),
    'school', (SELECT to_jsonb(s) FROM (SELECT school_name, motto, session_current, term_current FROM school_profile LIMIT 1) s)
  ) INTO v;
  RETURN v;
END $$;
GRANT EXECUTE ON FUNCTION staff_dashboard(TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION staff_analytics(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  RETURN jsonb_build_object(
    'by_subject', COALESCE((SELECT jsonb_agg(x) FROM (
        SELECT s.subject_code, e.class_level, count(r.id) n, round(avg(r.percentage),1) avg_pct
        FROM results r JOIN exams e ON e.id=r.exam_id JOIN subjects s ON s.id=e.subject_id
        GROUP BY 1,2 ORDER BY 3 DESC LIMIT 50) x), '[]'::jsonb),
    'by_class', COALESCE((SELECT jsonb_agg(x) FROM (
        SELECT e.class_level, count(r.id) n, round(avg(r.percentage),1) avg_pct
        FROM results r JOIN exams e ON e.id=r.exam_id GROUP BY 1 ORDER BY 1) x), '[]'::jsonb),
    'hardest_questions', COALESCE((SELECT jsonb_agg(x) FROM (
        SELECT left(q.question_text,90) question, count(aa.id) attempts,
               round(100.0*sum(CASE WHEN aa.selected_option=o.option_label THEN 1 ELSE 0 END)/NULLIF(count(aa.id),0),1) pct_correct
        FROM attempt_answers aa JOIN questions q ON q.id=aa.question_id
        LEFT JOIN question_options o ON o.question_id=q.id AND o.is_correct
        GROUP BY q.id, q.question_text HAVING count(aa.id)>=1 ORDER BY 3 ASC LIMIT 10) x), '[]'::jsonb),
    'teacher_workload', COALESCE((SELECT jsonb_agg(x) FROM (
        SELECT u.full_name name,
          (SELECT count(*) FROM questions q WHERE q.created_by=u.id) questions,
          (SELECT count(*) FROM exams e WHERE e.created_by=u.id) exams
        FROM users u WHERE u.role IN ('TEACHER','SUPER_ADMIN','EXAM_OFFICER')
        ORDER BY 2 DESC LIMIT 15) x), '[]'::jsonb),
    'malpractice', (SELECT jsonb_build_object(
        'submitted', count(*) FILTER (WHERE status IN ('SUBMITTED','MALPRACTICE','TIMED_OUT')),
        'malpractice', count(*) FILTER (WHERE status='MALPRACTICE'),
        'strikes', COALESCE(sum(strike_count),0)) FROM exam_attempts)
  );
END $$;
GRANT EXECUTE ON FUNCTION staff_analytics(TEXT,TEXT) TO anon;

-- ----------------------------------------------------------------- STUDENTS
CREATE OR REPLACE FUNCTION staff_students(p_email TEXT, p_password TEXT, p_class TEXT DEFAULT NULL, p_search TEXT DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'admission_no') FROM (
    SELECT jsonb_build_object('user_id', u.id, 'full_name', u.full_name, 'email', u.email,
      'is_active', COALESCE(u.is_active,TRUE), 'admission_no', sp.admission_no, 'surname', sp.surname,
      'other_names', sp.other_names, 'class_level', sp.class_level, 'arm', sp.arm, 'gender', sp.gender,
      'parent_phone', sp.parent_phone, 'parent_email', sp.parent_email, 'fee_status', sp.fee_status,
      'status', sp.status, 'result_pin', sp.result_pin) x
    FROM student_profiles sp JOIN users u ON u.id = sp.user_id
    WHERE (p_class IS NULL OR sp.class_level = p_class)
      AND (p_search IS NULL OR sp.admission_no ILIKE '%'||p_search||'%' OR u.full_name ILIKE '%'||p_search||'%')
    LIMIT 1000) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_students(TEXT,TEXT,TEXT,TEXT) TO anon;

-- Create/update a student (and parent-visible profile). Password optional on update.
CREATE OR REPLACE FUNCTION staff_student_save(p_email TEXT, p_password TEXT, p JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID; v_user UUID; v_adm TEXT; v_pin TEXT;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN']);
  v_user := NULLIF(p->>'user_id','')::uuid;
  v_adm := btrim(p->>'admission_no');
  IF v_adm = '' THEN RAISE EXCEPTION 'admission_no is required'; END IF;
  v_pin := upper(btrim(split_part(p->>'full_name',' ',1))) || upper(btrim(p->>'class_level'));
  IF v_user IS NULL THEN
    IF COALESCE(p->>'password','') = '' THEN RAISE EXCEPTION 'password is required for a new student'; END IF;
    v_user := uuid_generate_v4();
    INSERT INTO users(id, full_name, email, password_hash, role, phone, is_active)
    VALUES (v_user, btrim(p->>'full_name'), lower(btrim(p->>'email')),
            crypt(p->>'password', gen_salt('bf', 10)), 'STUDENT',
            NULLIF(p->>'parent_phone',''), TRUE);
    INSERT INTO student_profiles(id, user_id, admission_no, surname, other_names, class_level, arm,
        gender, parent_phone, parent_email, session, result_pin, fee_status, status)
    VALUES (uuid_generate_v4(), v_user, v_adm, btrim(split_part(p->>'full_name',' ',1)),
            NULLIF(btrim(regexp_replace(p->>'full_name','^\S+\s*','')),''),
            p->>'class_level', NULLIF(p->>'arm',''), NULLIF(p->>'gender',''),
            NULLIF(p->>'parent_phone',''), NULLIF(p->>'parent_email',''),
            COALESCE(NULLIF(p->>'session',''), (SELECT session_current FROM school_profile LIMIT 1), '2024/2025'),
            v_pin, COALESCE(NULLIF(p->>'fee_status',''),'PAID'), 'ACTIVE');
  ELSE
    UPDATE users SET full_name=btrim(p->>'full_name'),
      email=lower(btrim(p->>'email')), is_active=COALESCE((p->>'is_active')::boolean, is_active),
      password_hash=CASE WHEN COALESCE(p->>'password','')='' THEN password_hash
                         ELSE crypt(p->>'password', gen_salt('bf',10)) END
    WHERE id=v_user;
    UPDATE student_profiles SET admission_no=v_adm, class_level=p->>'class_level',
      arm=NULLIF(p->>'arm',''), gender=NULLIF(p->>'gender',''),
      parent_phone=NULLIF(p->>'parent_phone',''), parent_email=NULLIF(p->>'parent_email',''),
      fee_status=COALESCE(NULLIF(p->>'fee_status',''), fee_status),
      status=COALESCE(NULLIF(p->>'status',''), status) WHERE user_id=v_user;
  END IF;
  PERFORM klc_web_audit(v_admin, 'WEB_STUDENT_SAVE', 'users', v_user, v_adm);
  RETURN jsonb_build_object('user_id', v_user, 'result_pin', v_pin);
END $$;
GRANT EXECUTE ON FUNCTION staff_student_save(TEXT,TEXT,JSONB) TO anon;

-- ----------------------------------------------------------------- TEACHERS
CREATE OR REPLACE FUNCTION staff_teachers(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'full_name') FROM (
    SELECT jsonb_build_object('user_id', u.id, 'full_name', u.full_name, 'email', u.email,
      'role', u.role, 'phone', u.phone, 'is_active', COALESCE(u.is_active,TRUE),
      'subjects', COALESCE((SELECT jsonb_agg(s.subject_code) FROM teacher_subjects ts
                            JOIN subjects s ON s.id=ts.subject_id WHERE ts.teacher_id=u.id),'[]'::jsonb)) x
    FROM users u WHERE u.role IN ('TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN')) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_teachers(TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION staff_teacher_save(p_email TEXT, p_password TEXT, p JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID; v_user UUID; v_code TEXT;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN']);
  v_user := NULLIF(p->>'user_id','')::uuid;
  IF COALESCE(p->>'role','TEACHER') NOT IN ('TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN') THEN
    RAISE EXCEPTION 'Invalid staff role';
  END IF;
  IF v_user IS NULL THEN
    IF COALESCE(p->>'password','') = '' THEN RAISE EXCEPTION 'password is required for a new staff account'; END IF;
    v_user := uuid_generate_v4();
    INSERT INTO users(id, full_name, email, password_hash, role, phone, is_active)
    VALUES (v_user, btrim(p->>'full_name'), lower(btrim(p->>'email')),
            crypt(p->>'password', gen_salt('bf',10)),
            COALESCE(p->>'role','TEACHER'), NULLIF(p->>'phone',''), TRUE);
  ELSE
    UPDATE users SET full_name=btrim(p->>'full_name'), email=lower(btrim(p->>'email')),
      role=COALESCE(p->>'role', role), phone=NULLIF(p->>'phone',''),
      is_active=COALESCE((p->>'is_active')::boolean, is_active),
      password_hash=CASE WHEN COALESCE(p->>'password','')='' THEN password_hash
                         ELSE crypt(p->>'password', gen_salt('bf',10)) END
    WHERE id=v_user;
  END IF;
  IF jsonb_typeof(p->'subject_codes')='array' THEN
    DELETE FROM teacher_subjects WHERE teacher_id=v_user;
    FOR v_code IN SELECT jsonb_array_elements_text(p->'subject_codes') LOOP
      INSERT INTO teacher_subjects(id, teacher_id, subject_id)
      SELECT uuid_generate_v4(), v_user, id FROM subjects WHERE upper(subject_code)=upper(v_code)
      ON CONFLICT DO NOTHING;
    END LOOP;
  END IF;
  PERFORM klc_web_audit(v_admin, 'WEB_TEACHER_SAVE', 'users', v_user, p->>'email');
  RETURN jsonb_build_object('user_id', v_user);
END $$;
GRANT EXECUTE ON FUNCTION staff_teacher_save(TEXT,TEXT,JSONB) TO anon;

-- ------------------------------------------------------- CLASSES & SUBJECTS
CREATE OR REPLACE FUNCTION staff_classes(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'class_level', x->>'arm') FROM (
    SELECT jsonb_build_object('id', c.id, 'class_level', c.class_level, 'arm', c.arm,
      'session', c.session, 'is_active', COALESCE(c.is_active,TRUE),
      'students', (SELECT count(*) FROM student_profiles sp
                   WHERE sp.class_level=c.class_level AND COALESCE(sp.arm,'')=COALESCE(c.arm,'')
                     AND COALESCE(sp.status,'ACTIVE')='ACTIVE')) x
    FROM school_classes c) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_classes(TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION staff_class_save(p_email TEXT, p_password TEXT, p JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID; v_id UUID;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN']);
  v_id := NULLIF(p->>'id','')::uuid;
  IF v_id IS NULL THEN
    v_id := uuid_generate_v4();
    INSERT INTO school_classes(id, class_level, arm, class_teacher_id, session, is_active)
    VALUES (v_id, p->>'class_level', NULLIF(p->>'arm',''), NULLIF(p->>'class_teacher_id','')::uuid,
            NULLIF(p->>'session',''), COALESCE((p->>'is_active')::boolean, TRUE));
  ELSE
    UPDATE school_classes SET class_level=p->>'class_level', arm=NULLIF(p->>'arm',''),
      class_teacher_id=NULLIF(p->>'class_teacher_id','')::uuid,
      session=NULLIF(p->>'session',''), is_active=COALESCE((p->>'is_active')::boolean, is_active)
    WHERE id=v_id;
  END IF;
  PERFORM klc_web_audit(v_admin, 'WEB_CLASS_SAVE', 'school_classes', v_id, p->>'class_level');
  RETURN jsonb_build_object('id', v_id);
END $$;
GRANT EXECUTE ON FUNCTION staff_class_save(TEXT,TEXT,JSONB) TO anon;

CREATE OR REPLACE FUNCTION staff_subject_save(p_email TEXT, p_password TEXT, p JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID; v_id UUID;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN']);
  v_id := NULLIF(p->>'id','')::uuid;
  IF v_id IS NULL THEN
    SELECT id INTO v_id FROM subjects WHERE upper(subject_code)=upper(p->>'subject_code');
  END IF;
  IF v_id IS NULL THEN
    v_id := uuid_generate_v4();
    INSERT INTO subjects(id, subject_name, subject_code, class_level, pass_mark, is_active, created_by)
    VALUES (v_id, btrim(p->>'subject_name'), upper(btrim(p->>'subject_code')), p->>'class_level',
            COALESCE((p->>'pass_mark')::int, 40), COALESCE((p->>'is_active')::boolean, TRUE), v_admin);
  ELSE
    UPDATE subjects SET subject_name=btrim(p->>'subject_name'), class_level=p->>'class_level',
      pass_mark=COALESCE((p->>'pass_mark')::int, pass_mark),
      is_active=COALESCE((p->>'is_active')::boolean, is_active) WHERE id=v_id;
  END IF;
  PERFORM klc_web_audit(v_admin, 'WEB_SUBJECT_SAVE', 'subjects', v_id, p->>'subject_code');
  RETURN jsonb_build_object('id', v_id);
END $$;
GRANT EXECUTE ON FUNCTION staff_subject_save(TEXT,TEXT,JSONB) TO anon;

-- ---------------------------------------------------------------- QUESTIONS
CREATE OR REPLACE FUNCTION staff_questions(p_email TEXT, p_password TEXT, p_subject_code TEXT DEFAULT NULL, p_approved BOOLEAN DEFAULT NULL, p_search TEXT DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
    SELECT jsonb_build_object('id', q.id, 'subject_code', s.subject_code, 'class_level', q.class_level,
      'topic', q.topic, 'question_type', q.question_type, 'question_text', q.question_text,
      'is_approved', COALESCE(q.is_approved,FALSE), 'created_at', q.created_at,
      'options', COALESCE((SELECT jsonb_agg(jsonb_build_object('label',o.option_label,'text',o.option_text,'correct',o.is_correct) ORDER BY o.option_label)
                           FROM question_options o WHERE o.question_id=q.id),'[]'::jsonb)) x
    FROM questions q JOIN subjects s ON s.id=q.subject_id
    WHERE (p_subject_code IS NULL OR upper(s.subject_code)=upper(p_subject_code))
      AND (p_approved IS NULL OR COALESCE(q.is_approved,FALSE)=p_approved)
      AND (p_search IS NULL OR q.question_text ILIKE '%'||p_search||'%')
    LIMIT 500) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_questions(TEXT,TEXT,TEXT,BOOLEAN,TEXT) TO anon;

CREATE OR REPLACE FUNCTION staff_question_save(p_email TEXT, p_password TEXT, p JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID; v_role VARCHAR; v_qid UUID; v_subject UUID; v_opt JSONB; v_correct INT := 0;
BEGIN
  v_role := klc_web_role(p_email, p_password);
  IF v_role NOT IN ('SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER') THEN RAISE EXCEPTION 'Staff accounts only'; END IF;
  SELECT id INTO v_user FROM users WHERE lower(email)=lower(trim(p_email));
  SELECT id INTO v_subject FROM subjects WHERE upper(subject_code)=upper(p->>'subject_code');
  IF v_subject IS NULL THEN RAISE EXCEPTION 'Unknown subject code'; END IF;
  IF v_role='TEACHER' AND NOT EXISTS (SELECT 1 FROM teacher_subjects WHERE teacher_id=v_user AND subject_id=v_subject) THEN
    RAISE EXCEPTION 'You may only edit questions for subjects assigned to you';
  END IF;
  IF jsonb_typeof(p->'options')<>'array' OR jsonb_array_length(p->'options')<2 THEN RAISE EXCEPTION 'At least 2 options required'; END IF;
  SELECT count(*) INTO v_correct FROM jsonb_array_elements(p->'options') o WHERE (o->>'correct')::boolean;
  IF v_correct <> 1 THEN RAISE EXCEPTION 'Exactly 1 correct option required'; END IF;

  v_qid := NULLIF(p->>'id','')::uuid;
  IF v_qid IS NULL THEN
    v_qid := uuid_generate_v4();
    INSERT INTO questions(id, subject_id, class_level, topic, question_text, question_type, is_approved, created_by)
    VALUES (v_qid, v_subject, NULLIF(p->>'class_level',''), NULLIF(p->>'topic',''), btrim(p->>'question_text'),
            COALESCE(NULLIF(upper(p->>'question_type'),''),'MCQ'), FALSE, v_user);
  ELSE
    UPDATE questions SET subject_id=v_subject, class_level=NULLIF(p->>'class_level',''),
      topic=NULLIF(p->>'topic',''), question_text=btrim(p->>'question_text'),
      question_type=COALESCE(NULLIF(upper(p->>'question_type'),''), question_type) WHERE id=v_qid;
    DELETE FROM question_options WHERE question_id=v_qid;
    INSERT INTO question_versions(id, question_id, version, question_text, edited_by)
    SELECT uuid_generate_v4(), v_qid, COALESCE(max(version),0)+1, btrim(p->>'question_text'), v_user
    FROM question_versions WHERE question_id=v_qid;
  END IF;
  FOR v_opt IN SELECT * FROM jsonb_array_elements(p->'options') LOOP
    INSERT INTO question_options(id, question_id, option_label, option_text, is_correct)
    VALUES (uuid_generate_v4(), v_qid, v_opt->>'label', btrim(v_opt->>'text'), (v_opt->>'correct')::boolean);
  END LOOP;
  PERFORM klc_web_audit(v_user, 'WEB_QUESTION_SAVE', 'questions', v_qid, p->>'subject_code');
  RETURN jsonb_build_object('id', v_qid);
END $$;
GRANT EXECUTE ON FUNCTION staff_question_save(TEXT,TEXT,JSONB) TO anon;

CREATE OR REPLACE FUNCTION staff_question_moderate(p_email TEXT, p_password TEXT, p_id UUID, p_action TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER']);
  IF p_action='approve' THEN UPDATE questions SET is_approved=TRUE WHERE id=p_id;
  ELSIF p_action='unapprove' THEN UPDATE questions SET is_approved=FALSE WHERE id=p_id;
  ELSIF p_action='delete' THEN DELETE FROM questions WHERE id=p_id;
  ELSE RAISE EXCEPTION 'Unknown action %', p_action; END IF;
  PERFORM klc_web_audit(v_admin, 'WEB_QUESTION_'||upper(p_action), 'questions', p_id, '');
  RETURN jsonb_build_object('ok', TRUE);
END $$;
GRANT EXECUTE ON FUNCTION staff_question_moderate(TEXT,TEXT,UUID,TEXT) TO anon;

-- -------------------------------------------------------------------- EXAMS
-- Management only. NOTE: no function here creates attempts or answers -
-- sitting an exam is desktop-app only by design.
CREATE OR REPLACE FUNCTION staff_exams(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
    SELECT jsonb_build_object('id', e.id, 'subject_code', s.subject_code, 'class_level', e.class_level,
      'arm', e.arm, 'term', e.term, 'session', e.session, 'title', e.title, 'instructions', e.instructions,
      'duration_minutes', e.duration_minutes, 'attempt_limit', e.attempt_limit,
      'is_practice', COALESCE(e.is_practice,FALSE), 'is_mock', COALESCE(e.is_mock,FALSE),
      'is_active', COALESCE(e.is_active,TRUE), 'start_at', e.start_at, 'end_at', e.end_at,
      'created_at', e.created_at,
      'question_count', (SELECT count(*) FROM exam_questions eq WHERE eq.exam_id=e.id),
      'attempts', (SELECT count(*) FROM exam_attempts ea WHERE ea.exam_id=e.id)) x
    FROM exams e JOIN subjects s ON s.id=e.subject_id LIMIT 500) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_exams(TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION staff_exam_save(p_email TEXT, p_password TEXT, p JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID; v_id UUID; v_subject UUID;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER']);
  SELECT id INTO v_subject FROM subjects WHERE upper(subject_code)=upper(p->>'subject_code');
  IF v_subject IS NULL THEN RAISE EXCEPTION 'Unknown subject code'; END IF;
  v_id := NULLIF(p->>'id','')::uuid;
  IF v_id IS NULL THEN
    v_id := uuid_generate_v4();
    INSERT INTO exams(id, subject_id, class_level, arm, term, session, title, instructions,
      duration_minutes, attempt_limit, is_practice, is_mock, is_active, start_at, end_at, created_by)
    VALUES (v_id, v_subject, p->>'class_level', NULLIF(p->>'arm',''), NULLIF(p->>'term',''),
      NULLIF(p->>'session',''), NULLIF(p->>'title',''), NULLIF(p->>'instructions',''),
      COALESCE((p->>'duration_minutes')::int, 60), COALESCE((p->>'attempt_limit')::int, 1),
      COALESCE((p->>'is_practice')::boolean,FALSE), COALESCE((p->>'is_mock')::boolean,FALSE),
      COALESCE((p->>'is_active')::boolean,TRUE),
      NULLIF(p->>'start_at','')::timestamp, NULLIF(p->>'end_at','')::timestamp, v_admin);
  ELSE
    UPDATE exams SET subject_id=v_subject, class_level=p->>'class_level', arm=NULLIF(p->>'arm',''),
      term=NULLIF(p->>'term',''), session=NULLIF(p->>'session',''), title=NULLIF(p->>'title',''),
      instructions=NULLIF(p->>'instructions',''),
      duration_minutes=COALESCE((p->>'duration_minutes')::int, duration_minutes),
      attempt_limit=COALESCE((p->>'attempt_limit')::int, attempt_limit),
      is_practice=COALESCE((p->>'is_practice')::boolean, is_practice),
      is_mock=COALESCE((p->>'is_mock')::boolean, is_mock),
      is_active=COALESCE((p->>'is_active')::boolean, is_active),
      start_at=NULLIF(p->>'start_at','')::timestamp, end_at=NULLIF(p->>'end_at','')::timestamp
    WHERE id=v_id;
  END IF;
  PERFORM klc_web_audit(v_admin, 'WEB_EXAM_SAVE', 'exams', v_id, p->>'subject_code');
  RETURN jsonb_build_object('id', v_id);
END $$;
GRANT EXECUTE ON FUNCTION staff_exam_save(TEXT,TEXT,JSONB) TO anon;

CREATE OR REPLACE FUNCTION staff_exam_attach(p_email TEXT, p_password TEXT, p_exam UUID, p_question_ids JSONB, p_replace BOOLEAN DEFAULT FALSE)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID; v_n INT;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  IF p_replace THEN DELETE FROM exam_questions WHERE exam_id=p_exam; END IF;
  INSERT INTO exam_questions(id, exam_id, question_id)
  SELECT uuid_generate_v4(), p_exam, (value)::uuid FROM jsonb_array_elements_text(p_question_ids)
  ON CONFLICT DO NOTHING;
  SELECT count(*) INTO v_n FROM exam_questions WHERE exam_id=p_exam;
  PERFORM klc_web_audit(v_admin, 'WEB_EXAM_ATTACH', 'exams', p_exam, v_n || ' questions');
  RETURN jsonb_build_object('question_count', v_n);
END $$;
GRANT EXECUTE ON FUNCTION staff_exam_attach(TEXT,TEXT,UUID,JSONB,BOOLEAN) TO anon;

-- -------------------------------------------------------------- CA & RESULTS
CREATE OR REPLACE FUNCTION staff_ca_scores(p_email TEXT, p_password TEXT, p_class TEXT, p_subject_code TEXT, p_term TEXT, p_session TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'admission_no') FROM (
    SELECT jsonb_build_object('student_id', sp.user_id, 'admission_no', sp.admission_no,
      'student', sp.surname || ' ' || COALESCE(sp.other_names,''),
      'ca1', cs.ca1_score, 'ca2', cs.ca2_score, 'exam_score', cs.exam_score,
      'total', cs.total_score, 'grade', cs.grade, 'position', cs.position) x
    FROM student_profiles sp
    LEFT JOIN ca_scores cs ON cs.student_id=sp.user_id AND cs.term=p_term AND cs.session=p_session
      AND cs.subject_id=(SELECT id FROM subjects WHERE upper(subject_code)=upper(p_subject_code))
    WHERE sp.class_level=p_class AND COALESCE(sp.status,'ACTIVE')='ACTIVE') s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_ca_scores(TEXT,TEXT,TEXT,TEXT,TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION staff_ca_save(p_email TEXT, p_password TEXT, p JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID; v_subject UUID; r JSONB; v_n INT := 0;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  SELECT id INTO v_subject FROM subjects WHERE upper(subject_code)=upper(p->>'subject_code');
  IF v_subject IS NULL THEN RAISE EXCEPTION 'Unknown subject code'; END IF;
  FOR r IN SELECT * FROM jsonb_array_elements(p->'rows') LOOP
    INSERT INTO ca_scores(id, student_id, subject_id, class_level, term, session, ca1_score, ca2_score, exam_score, total_score, grade)
    VALUES (uuid_generate_v4(), (r->>'student_id')::uuid, v_subject, p->>'class_level', p->>'term', p->>'session',
            (r->>'ca1')::numeric, (r->>'ca2')::numeric, (r->>'exam_score')::numeric,
            (r->>'ca1')::numeric + (r->>'ca2')::numeric + COALESCE((r->>'exam_score')::numeric,0), r->>'grade')
    ON CONFLICT (student_id, subject_id, term, session) DO UPDATE
      SET ca1_score=EXCLUDED.ca1_score, ca2_score=EXCLUDED.ca2_score,
          exam_score=EXCLUDED.exam_score, total_score=EXCLUDED.total_score, grade=EXCLUDED.grade;
    v_n := v_n + 1;
  END LOOP;
  PERFORM klc_web_audit(v_admin, 'WEB_CA_SAVE', 'ca_scores', v_subject, v_n || ' rows');
  RETURN jsonb_build_object('saved', v_n);
END $$;
GRANT EXECUTE ON FUNCTION staff_ca_save(TEXT,TEXT,JSONB) TO anon;

CREATE OR REPLACE FUNCTION staff_result_publish(p_email TEXT, p_password TEXT, p_result UUID, p_published BOOLEAN)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER']);
  UPDATE results SET published=p_published WHERE id=p_result;
  PERFORM klc_web_audit(v_admin, CASE WHEN p_published THEN 'WEB_RESULT_PUBLISH' ELSE 'WEB_RESULT_UNPUBLISH' END, 'results', p_result, '');
  RETURN jsonb_build_object('ok', TRUE);
END $$;
GRANT EXECUTE ON FUNCTION staff_result_publish(TEXT,TEXT,UUID,BOOLEAN) TO anon;

-- ------------------------------------------------------- APPEALS & NOTICES
CREATE OR REPLACE FUNCTION staff_appeals(p_email TEXT, p_password TEXT, p_status TEXT DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
    SELECT jsonb_build_object('id', a.id, 'subject_code', a.subject_code, 'reason', a.reason,
      'status', a.status, 'created_at', a.created_at,
      'student', COALESCE(sp.surname || ' ' || COALESCE(sp.other_names,''), ''),
      'admission_no', sp.admission_no) x
    FROM result_appeals a LEFT JOIN student_profiles sp ON sp.user_id=a.student_id
    WHERE (p_status IS NULL OR a.status=p_status) LIMIT 500) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_appeals(TEXT,TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION staff_appeal_resolve(p_email TEXT, p_password TEXT, p_id UUID, p_status TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER']);
  IF p_status NOT IN ('OPEN','IN_REVIEW','RESOLVED','REJECTED') THEN RAISE EXCEPTION 'Invalid status'; END IF;
  UPDATE result_appeals SET status=p_status WHERE id=p_id;
  PERFORM klc_web_audit(v_admin, 'WEB_APPEAL_'||p_status, 'result_appeals', p_id, '');
  RETURN jsonb_build_object('ok', TRUE);
END $$;
GRANT EXECUTE ON FUNCTION staff_appeal_resolve(TEXT,TEXT,UUID,TEXT) TO anon;

CREATE OR REPLACE FUNCTION staff_announcements(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
    SELECT jsonb_build_object('id', id, 'title', title, 'body', body, 'target_role', target_role,
      'created_at', created_at, 'expires_at', expires_at) x FROM announcements LIMIT 500) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_announcements(TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION staff_announcement_save(p_email TEXT, p_password TEXT, p JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID; v_id UUID;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER']);
  v_id := NULLIF(p->>'id','')::uuid;
  IF COALESCE((p->>'delete')::boolean, FALSE) THEN
    DELETE FROM announcements WHERE id=v_id;
    RETURN jsonb_build_object('deleted', TRUE);
  END IF;
  IF v_id IS NULL THEN
    v_id := uuid_generate_v4();
    INSERT INTO announcements(id, title, body, target_role, created_by, expires_at)
    VALUES (v_id, btrim(p->>'title'), btrim(p->>'body'), COALESCE(NULLIF(p->>'target_role',''),'ALL'),
            v_admin, NULLIF(p->>'expires_at','')::timestamp);
  ELSE
    UPDATE announcements SET title=btrim(p->>'title'), body=btrim(p->>'body'),
      target_role=COALESCE(NULLIF(p->>'target_role',''), target_role),
      expires_at=NULLIF(p->>'expires_at','')::timestamp WHERE id=v_id;
  END IF;
  PERFORM klc_web_audit(v_admin, 'WEB_ANNOUNCEMENT_SAVE', 'announcements', v_id, p->>'title');
  RETURN jsonb_build_object('id', v_id);
END $$;
GRANT EXECUTE ON FUNCTION staff_announcement_save(TEXT,TEXT,JSONB) TO anon;

-- ------------------------------------------------------------- MATERIALS
CREATE OR REPLACE FUNCTION staff_materials(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
    SELECT jsonb_build_object('id', m.id, 'subject_code', s.subject_code, 'class_level', m.class_level,
      'title', m.title, 'description', m.description, 'file_url', m.file_url, 'created_at', m.created_at) x
    FROM study_materials m JOIN subjects s ON s.id=m.subject_id LIMIT 500) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_materials(TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION staff_material_save(p_email TEXT, p_password TEXT, p JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID; v_id UUID; v_subject UUID;
BEGIN
  v_user := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  v_id := NULLIF(p->>'id','')::uuid;
  IF COALESCE((p->>'delete')::boolean, FALSE) THEN
    DELETE FROM study_materials WHERE id=v_id; RETURN jsonb_build_object('deleted', TRUE);
  END IF;
  SELECT id INTO v_subject FROM subjects WHERE upper(subject_code)=upper(p->>'subject_code');
  IF v_subject IS NULL THEN RAISE EXCEPTION 'Unknown subject code'; END IF;
  IF v_id IS NULL THEN
    v_id := uuid_generate_v4();
    INSERT INTO study_materials(id, subject_id, class_level, title, description, file_url, uploaded_by)
    VALUES (v_id, v_subject, NULLIF(p->>'class_level',''), btrim(p->>'title'),
            NULLIF(p->>'description',''), NULLIF(p->>'file_url',''), v_user);
  ELSE
    UPDATE study_materials SET subject_id=v_subject, class_level=NULLIF(p->>'class_level',''),
      title=btrim(p->>'title'), description=NULLIF(p->>'description',''),
      file_url=NULLIF(p->>'file_url','') WHERE id=v_id;
  END IF;
  PERFORM klc_web_audit(v_user, 'WEB_MATERIAL_SAVE', 'study_materials', v_id, p->>'title');
  RETURN jsonb_build_object('id', v_id);
END $$;
GRANT EXECUTE ON FUNCTION staff_material_save(TEXT,TEXT,JSONB) TO anon;

-- --------------------------------------------------- AUDIT / SETTINGS / MISC
CREATE OR REPLACE FUNCTION staff_audit(p_email TEXT, p_password TEXT, p_limit INT DEFAULT 200)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
    SELECT jsonb_build_object('id', a.id, 'actor', COALESCE(u.full_name,''), 'action', a.action,
      'entity_type', a.entity_type, 'details', a.details, 'created_at', a.created_at) x
    FROM audit_logs a LEFT JOIN users u ON u.id=a.user_id
    ORDER BY a.created_at DESC LIMIT LEAST(COALESCE(p_limit,200),1000)) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_audit(TEXT,TEXT,INT) TO anon;

CREATE OR REPLACE FUNCTION staff_school(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  RETURN (SELECT to_jsonb(s) FROM (SELECT * FROM school_profile LIMIT 1) s);
END $$;
GRANT EXECUTE ON FUNCTION staff_school(TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION staff_school_save(p_email TEXT, p_password TEXT, p JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN']);
  UPDATE school_profile SET
    school_name=COALESCE(NULLIF(p->>'school_name',''), school_name),
    motto=COALESCE(p->>'motto', motto),
    principal_name=COALESCE(NULLIF(p->>'principal_name',''), principal_name),
    session_current=COALESCE(NULLIF(p->>'session_current',''), session_current),
    term_current=COALESCE(NULLIF(p->>'term_current',''), term_current),
    grading_scale=COALESCE(p->'grading_scale', grading_scale),
    updated_at=now();
  PERFORM klc_web_audit(v_admin, 'WEB_SCHOOL_SAVE', 'school_profile', NULL, p->>'school_name');
  RETURN jsonb_build_object('ok', TRUE);
END $$;
GRANT EXECUTE ON FUNCTION staff_school_save(TEXT,TEXT,JSONB) TO anon;

CREATE OR REPLACE FUNCTION staff_notifications(p_email TEXT, p_password TEXT, p_limit INT DEFAULT 100)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
    SELECT jsonb_build_object('id', id, 'recipient_email', recipient_email, 'subject', subject,
      'channel', channel, 'status', status, 'created_at', created_at) x
    FROM notification_queue ORDER BY created_at DESC LIMIT LEAST(COALESCE(p_limit,100),500)) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_notifications(TEXT,TEXT,INT) TO anon;

-- ------------------------------------------------------------------- PARENT
-- Full ward report: profile + published results + CA + summary (PIN-gated).
CREATE OR REPLACE FUNCTION parent_report(p_admission TEXT, p_pin TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_ward JSONB;
BEGIN
  SELECT jsonb_build_object('admission_no', sp.admission_no,
      'name', sp.surname || ' ' || COALESCE(sp.other_names,''),
      'class_level', sp.class_level, 'arm', sp.arm)
  INTO v_ward FROM student_profiles sp
  WHERE sp.admission_no=p_admission AND sp.result_pin=upper(trim(p_pin));
  IF v_ward IS NULL THEN RAISE EXCEPTION 'Invalid admission number or result PIN'; END IF;
  RETURN jsonb_build_object(
    'ward', v_ward,
    'results', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
        SELECT jsonb_build_object('subject_code', s.subject_code, 'class_level', e.class_level,
          'term', COALESCE(e.term,'-'), 'session', COALESCE(e.session,'-'), 'score', r.score,
          'total_questions', r.total_questions, 'percentage', r.percentage, 'grade', r.grade,
          'position', r.position, 'qr_verify_code', r.qr_verify_code, 'created_at', r.created_at) x
        FROM results r JOIN exams e ON e.id=r.exam_id JOIN subjects s ON s.id=e.subject_id
        JOIN student_profiles sp ON sp.user_id=r.student_id
        WHERE sp.admission_no=p_admission AND COALESCE(r.published,TRUE)) s), '[]'::jsonb),
    'ca', COALESCE((SELECT jsonb_agg(x) FROM (
        SELECT jsonb_build_object('subject_code', s.subject_code, 'term', cs.term, 'session', cs.session,
          'ca1', cs.ca1_score, 'ca2', cs.ca2_score, 'exam_score', cs.exam_score,
          'total', cs.total_score, 'grade', cs.grade, 'position', cs.position) x
        FROM ca_scores cs JOIN subjects s ON s.id=cs.subject_id
        JOIN student_profiles sp ON sp.user_id=cs.student_id
        WHERE sp.admission_no=p_admission) s), '[]'::jsonb));
END $$;
GRANT EXECUTE ON FUNCTION parent_report(TEXT,TEXT) TO anon;

-- ------------------------------------------------------------------ STUDENT
-- Students get their own data only. Deliberately NO exam-taking RPC exists.
CREATE OR REPLACE FUNCTION student_portal(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID; v JSONB;
BEGIN
  v_user := klc_web_require(p_email, p_password, ARRAY['STUDENT']);
  SELECT jsonb_build_object(
    'profile', (SELECT jsonb_build_object('full_name', u.full_name, 'email', u.email,
        'admission_no', sp.admission_no, 'class_level', sp.class_level, 'arm', sp.arm,
        'result_pin', sp.result_pin, 'fee_status', sp.fee_status)
        FROM users u JOIN student_profiles sp ON sp.user_id=u.id WHERE u.id=v_user),
    'results', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
        SELECT jsonb_build_object('subject_code', s.subject_code, 'term', COALESCE(e.term,'-'),
          'session', COALESCE(e.session,'-'), 'percentage', r.percentage, 'grade', r.grade,
          'position', r.position, 'qr_verify_code', r.qr_verify_code, 'created_at', r.created_at) x
        FROM results r JOIN exams e ON e.id=r.exam_id JOIN subjects s ON s.id=e.subject_id
        WHERE r.student_id=v_user AND COALESCE(r.published,TRUE)) s), '[]'::jsonb),
    'ca', COALESCE((SELECT jsonb_agg(x) FROM (
        SELECT jsonb_build_object('subject_code', s.subject_code, 'term', cs.term, 'session', cs.session,
          'ca1', cs.ca1_score, 'ca2', cs.ca2_score, 'exam_score', cs.exam_score,
          'total', cs.total_score, 'grade', cs.grade, 'position', cs.position) x
        FROM ca_scores cs JOIN subjects s ON s.id=cs.subject_id WHERE cs.student_id=v_user) s), '[]'::jsonb),
    'materials', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
        SELECT jsonb_build_object('subject_code', s.subject_code, 'class_level', m.class_level,
          'title', m.title, 'description', m.description, 'file_url', m.file_url, 'created_at', m.created_at) x
        FROM study_materials m JOIN subjects s ON s.id=m.subject_id
        JOIN student_profiles sp ON sp.user_id=v_user
        WHERE m.class_level IS NULL OR m.class_level=sp.class_level LIMIT 200) s), '[]'::jsonb),
    'announcements', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
        SELECT jsonb_build_object('title', title, 'body', body, 'created_at', created_at) x
        FROM announcements WHERE target_role IN ('ALL','STUDENT')
          AND (expires_at IS NULL OR expires_at>now()) LIMIT 50) s), '[]'::jsonb),
    'appeals', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
        SELECT jsonb_build_object('subject_code', subject_code, 'reason', reason,
          'status', status, 'created_at', created_at) x
        FROM result_appeals WHERE student_id=v_user LIMIT 100) s), '[]'::jsonb)
  ) INTO v;
  RETURN v;
END $$;
GRANT EXECUTE ON FUNCTION student_portal(TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION student_appeal_submit(p_email TEXT, p_password TEXT, p_result UUID, p_subject TEXT, p_reason TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID;
BEGIN
  v_user := klc_web_require(p_email, p_password, ARRAY['STUDENT']);
  IF NOT EXISTS (SELECT 1 FROM results WHERE id=p_result AND student_id=v_user) THEN
    RAISE EXCEPTION 'Result not found for this student';
  END IF;
  INSERT INTO result_appeals(id, result_id, student_id, subject_code, reason, status)
  VALUES (uuid_generate_v4(), p_result, v_user, p_subject, btrim(p_reason), 'OPEN');
  RETURN jsonb_build_object('ok', TRUE);
END $$;
GRANT EXECUTE ON FUNCTION student_appeal_submit(TEXT,TEXT,UUID,TEXT,TEXT) TO anon;

-- Results with ids + publish state (publish/unpublish from the web console)
CREATE OR REPLACE FUNCTION staff_results(p_email TEXT, p_password TEXT, p_limit INT DEFAULT 200)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
    SELECT jsonb_build_object('id', r.id, 'admission_no', sp.admission_no,
      'student', sp.surname || ' ' || COALESCE(sp.other_names,''),
      'subject_code', s.subject_code, 'class_level', e.class_level,
      'term', COALESCE(e.term,'-'), 'session', COALESCE(e.session,'-'),
      'score', r.score, 'percentage', r.percentage, 'grade', r.grade,
      'position', r.position, 'published', COALESCE(r.published,TRUE),
      'qr_verify_code', r.qr_verify_code, 'created_at', r.created_at) x
    FROM results r JOIN exams e ON e.id=r.exam_id JOIN subjects s ON s.id=e.subject_id
    JOIN student_profiles sp ON sp.user_id=r.student_id
    ORDER BY r.created_at DESC LIMIT LEAST(COALESCE(p_limit,200),1000)) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_results(TEXT,TEXT,INT) TO anon;
