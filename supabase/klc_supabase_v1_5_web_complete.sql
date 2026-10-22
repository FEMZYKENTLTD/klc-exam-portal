-- ============================================================================
-- KLC CBT Suite - v1.5 WEB PORTAL COMPLETION
-- Mirrors every remaining desktop feature on the web EXCEPT sitting exams.
-- Exams are VISIBLE on the web (students see what is scheduled for them) but
-- there is deliberately NO exam-start / answer-submit RPC: clicking an exam
-- on the web shows the "download the desktop app" dialog. Taking an exam
-- requires the proctored desktop app (kiosk lockdown, focus-loss strikes,
-- webcam evidence, offline autosave) and that guarantee must not be
-- weakened by a browser path.
-- All functions: SECURITY DEFINER, bcrypt credential + role check per call,
-- search_path includes `extensions` (pgcrypto lives there on Supabase).
-- Idempotent; safe to re-run.
-- ============================================================================

create extension if not exists pgcrypto;

-- ── Student exam catalogue (READ-ONLY: no attempt can start from here) ──────
CREATE OR REPLACE FUNCTION student_exams(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID; v_class TEXT; v_arm TEXT;
BEGIN
  v_user := klc_web_require(p_email, p_password, ARRAY['STUDENT']);
  SELECT class_level, arm INTO v_class, v_arm FROM student_profiles WHERE user_id = v_user;
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'start_at' NULLS LAST, x->>'subject_code') FROM (
    SELECT jsonb_build_object(
      'id', e.id, 'title', COALESCE(e.title, s.subject_name || ' Exam'),
      'subject_code', s.subject_code, 'subject_name', s.subject_name,
      'class_level', e.class_level, 'arm', e.arm,
      'term', e.term, 'session', e.session,
      'duration_minutes', e.duration_minutes,
      'attempt_limit', e.attempt_limit,
      'is_practice', COALESCE(e.is_practice, FALSE),
      'is_mock', COALESCE(e.is_mock, FALSE),
      'start_at', e.start_at, 'end_at', e.end_at,
      'instructions', e.instructions,
      'question_count', (SELECT count(*) FROM exam_questions eq WHERE eq.exam_id = e.id),
      'attempts_used', (SELECT count(*) FROM exam_attempts ea
                          WHERE ea.exam_id = e.id AND ea.student_id = v_user
                            AND ea.status IN ('SUBMITTED','MALPRACTICE','TIMED_OUT','MOCK_SUBMITTED')),
      'availability', CASE
        WHEN e.start_at IS NOT NULL AND now() < e.start_at THEN 'SCHEDULED'
        WHEN e.end_at IS NOT NULL AND now() > e.end_at THEN 'CLOSED'
        ELSE 'OPEN' END) x
    FROM exams e JOIN subjects s ON s.id = e.subject_id
    WHERE COALESCE(e.is_active, TRUE)
      AND e.class_level = v_class
      AND (e.arm IS NULL OR e.arm = '' OR v_arm IS NULL OR e.arm = v_arm)) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION student_exams(TEXT,TEXT) TO anon;

-- ── Exam question list / detach (management side) ────────────────────────────
CREATE OR REPLACE FUNCTION staff_exam_questions(p_email TEXT, p_password TEXT, p_exam UUID)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'question_text') FROM (
    SELECT jsonb_build_object('question_id', q.id, 'subject_code', s.subject_code,
      'class_level', q.class_level, 'topic', q.topic,
      'question_text', left(q.question_text, 160), 'is_approved', COALESCE(q.is_approved, FALSE)) x
    FROM exam_questions eq JOIN questions q ON q.id = eq.question_id
    JOIN subjects s ON s.id = q.subject_id WHERE eq.exam_id = p_exam) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_exam_questions(TEXT,TEXT,UUID) TO anon;

CREATE OR REPLACE FUNCTION staff_exam_detach(p_email TEXT, p_password TEXT, p_exam UUID, p_question UUID)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_admin UUID;
BEGIN
  v_admin := klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  DELETE FROM exam_questions WHERE exam_id = p_exam AND question_id = p_question;
  PERFORM klc_web_audit(v_admin, 'WEB_EXAM_DETACH', 'exams', p_exam, p_question::text);
  RETURN jsonb_build_object('ok', TRUE);
END $$;
GRANT EXECUTE ON FUNCTION staff_exam_detach(TEXT,TEXT,UUID,UUID) TO anon;

-- ── Question version history ─────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION staff_question_versions(p_email TEXT, p_password TEXT, p_question UUID)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
BEGIN
  PERFORM klc_web_require(p_email, p_password, ARRAY['SUPER_ADMIN','PRINCIPAL_ADMIN','EXAM_OFFICER','TEACHER']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY (x->>'version')::int DESC) FROM (
    SELECT jsonb_build_object('version', v.version, 'question_text', left(v.question_text, 300),
      'editor', COALESCE(u.full_name, ''), 'edited_at', v.created_at) x
    FROM question_versions v LEFT JOIN users u ON u.id = v.edited_by
    WHERE v.question_id = p_question) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION staff_question_versions(TEXT,TEXT,UUID) TO anon;

-- ── Account: profile, password, 2FA (TOTP) ───────────────────────────────────
CREATE OR REPLACE FUNCTION web_profile(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  RETURN (SELECT jsonb_build_object(
    'user_id', u.id, 'full_name', u.full_name, 'email', u.email, 'role', u.role,
    'phone', u.phone, 'totp_enabled', COALESCE(u.totp_enabled, FALSE),
    'bio', up.bio, 'date_of_birth', up.date_of_birth, 'address', up.address, 'photo_url', up.photo_url,
    'admission_no', sp.admission_no, 'class_level', sp.class_level, 'arm', sp.arm,
    'result_pin', sp.result_pin, 'fee_status', sp.fee_status)
    FROM users u
    LEFT JOIN user_profiles up ON up.user_id = u.id
    LEFT JOIN student_profiles sp ON sp.user_id = u.id
    WHERE u.id = v_user);
END $$;
GRANT EXECUTE ON FUNCTION web_profile(TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION web_profile_save(p_email TEXT, p_password TEXT, p JSONB)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  UPDATE users SET phone = COALESCE(NULLIF(p->>'phone',''), phone),
                   full_name = COALESCE(NULLIF(btrim(p->>'full_name'),''), full_name)
   WHERE id = v_user;
  INSERT INTO user_profiles(id, user_id, bio, date_of_birth, address, photo_url, updated_at)
  VALUES (uuid_generate_v4(), v_user, NULLIF(p->>'bio',''),
          NULLIF(p->>'date_of_birth','')::date, NULLIF(p->>'address',''),
          NULLIF(p->>'photo_url',''), now())
  ON CONFLICT (user_id) DO UPDATE SET
    bio = COALESCE(EXCLUDED.bio, user_profiles.bio),
    date_of_birth = COALESCE(EXCLUDED.date_of_birth, user_profiles.date_of_birth),
    address = COALESCE(EXCLUDED.address, user_profiles.address),
    photo_url = COALESCE(EXCLUDED.photo_url, user_profiles.photo_url),
    updated_at = now();
  RETURN jsonb_build_object('ok', TRUE);
END $$;
GRANT EXECUTE ON FUNCTION web_profile_save(TEXT,TEXT,JSONB) TO anon;

CREATE OR REPLACE FUNCTION web_change_password(p_email TEXT, p_password TEXT, p_new_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  IF length(COALESCE(p_new_password, '')) < 8 THEN
    RAISE EXCEPTION 'New password must be at least 8 characters';
  END IF;
  UPDATE users SET password_hash = crypt(p_new_password, gen_salt('bf', 10))
   WHERE id = v_user;
  PERFORM klc_web_audit(v_user, 'WEB_PASSWORD_CHANGE', 'users', v_user, '');
  RETURN jsonb_build_object('ok', TRUE);
END $$;
GRANT EXECUTE ON FUNCTION web_change_password(TEXT,TEXT,TEXT) TO anon;

-- TOTP (RFC 6238, SHA-1, 30 s step, ±1 window) implemented server-side so the
-- secret never has to be trusted to client-side verification.
CREATE OR REPLACE FUNCTION klc_totp_code(p_secret_b32 TEXT, p_counter BIGINT)
RETURNS TEXT LANGUAGE plpgsql IMMUTABLE SET search_path = public, extensions AS $$
DECLARE v_key BYTEA := ''::bytea; v_alphabet TEXT := 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  v_bits TEXT := ''; v_ch TEXT; v_pos INT; v_bytes INT; v_i INT; v_val BIGINT := 0;
  v_msg BYTEA; v_hash BYTEA; v_off INT; v_code BIGINT;
BEGIN
  FOR v_i IN 1..length(regexp_replace(upper(p_secret_b32), '\s', '', 'g')) LOOP
    v_ch := substr(regexp_replace(upper(p_secret_b32), '\s', '', 'g'), v_i, 1);
    v_pos := position(v_ch IN v_alphabet);
    IF v_pos = 0 THEN CONTINUE; END IF;
    v_bits := v_bits || (v_pos - 1)::bit(5)::text;
  END LOOP;
  v_bytes := length(v_bits) / 8;
  FOR v_i IN 0..v_bytes - 1 LOOP
    v_val := 0;
    FOR v_pos IN 0..7 LOOP
      v_val := v_val * 2 + (CASE WHEN substr(v_bits, v_i * 8 + v_pos + 1, 1) = '1' THEN 1 ELSE 0 END);
    END LOOP;
    v_key := v_key || decode(lpad(to_hex(v_val), 2, '0'), 'hex');
  END LOOP;
  v_msg := decode(lpad(to_hex(p_counter), 16, '0'), 'hex');
  v_hash := hmac(v_msg, v_key, 'sha1');
  v_off := get_byte(v_hash, 19) % 16;
  v_code := ((get_byte(v_hash, v_off) % 128) * 16777216)
          + (get_byte(v_hash, v_off + 1) * 65536)
          + (get_byte(v_hash, v_off + 2) * 256)
          + get_byte(v_hash, v_off + 3);
  RETURN lpad((v_code % 1000000)::text, 6, '0');
END $$;

CREATE OR REPLACE FUNCTION klc_totp_verify(p_secret_b32 TEXT, p_code TEXT)
RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE SET search_path = public, extensions AS $$
DECLARE v_counter BIGINT := floor(extract(epoch FROM now()) / 30)::bigint; v_i INT;
BEGIN
  FOR v_i IN -1..1 LOOP
    IF klc_totp_code(p_secret_b32, v_counter + v_i) = lpad(trim(p_code), 6, '0') THEN RETURN TRUE; END IF;
  END LOOP;
  RETURN FALSE;
END $$;

CREATE OR REPLACE FUNCTION web_totp_setup(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID; v_secret TEXT; v_alphabet TEXT := 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'; v_i INT;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  v_secret := '';
  FOR v_i IN 1..26 LOOP
    v_secret := v_secret || substr(v_alphabet, 1 + floor(random() * 32)::int, 1);
  END LOOP;
  -- Stored but NOT enabled until the user proves possession with a valid code.
  UPDATE users SET totp_secret = v_secret, totp_enabled = FALSE WHERE id = v_user;
  RETURN jsonb_build_object('secret', v_secret,
    'otpauth_url', 'otpauth://totp/KLC%20CBT:' || replace(p_email, '@', '%40')
      || '?secret=' || v_secret || '&issuer=KLC%20CBT');
END $$;
GRANT EXECUTE ON FUNCTION web_totp_setup(TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION web_totp_enable(p_email TEXT, p_password TEXT, p_code TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID; v_secret TEXT;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  SELECT totp_secret INTO v_secret FROM users WHERE id = v_user;
  IF v_secret IS NULL OR NOT klc_totp_verify(v_secret, p_code) THEN
    RAISE EXCEPTION 'Invalid authenticator code - check the code and try again';
  END IF;
  UPDATE users SET totp_enabled = TRUE WHERE id = v_user;
  PERFORM klc_web_audit(v_user, 'WEB_2FA_ENABLE', 'users', v_user, '');
  RETURN jsonb_build_object('ok', TRUE, 'totp_enabled', TRUE);
END $$;
GRANT EXECUTE ON FUNCTION web_totp_enable(TEXT,TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION web_totp_disable(p_email TEXT, p_password TEXT, p_code TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID; v_secret TEXT;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  SELECT totp_secret INTO v_secret FROM users WHERE id = v_user;
  IF v_secret IS NULL OR NOT klc_totp_verify(v_secret, p_code) THEN
    RAISE EXCEPTION 'Invalid authenticator code';
  END IF;
  UPDATE users SET totp_enabled = FALSE, totp_secret = NULL WHERE id = v_user;
  PERFORM klc_web_audit(v_user, 'WEB_2FA_DISABLE', 'users', v_user, '');
  RETURN jsonb_build_object('ok', TRUE, 'totp_enabled', FALSE);
END $$;
GRANT EXECUTE ON FUNCTION web_totp_disable(TEXT,TEXT,TEXT) TO anon;

-- ── Social: directory, friends, messages (desktop Friends/Messages parity) ──
CREATE OR REPLACE FUNCTION web_directory(p_email TEXT, p_password TEXT, p_search TEXT DEFAULT NULL)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'full_name') FROM (
    SELECT jsonb_build_object('user_id', u.id, 'full_name', u.full_name, 'role', u.role,
      'class_level', sp.class_level, 'arm', sp.arm,
      'friend_status', (SELECT f.status FROM friendships f
                         WHERE (f.requester_id = v_user AND f.receiver_id = u.id)
                            OR (f.requester_id = u.id AND f.receiver_id = v_user)
                         ORDER BY f.created_at DESC LIMIT 1)) x
    FROM users u LEFT JOIN student_profiles sp ON sp.user_id = u.id
    WHERE u.id <> v_user AND COALESCE(u.is_active, TRUE)
      AND (p_search IS NULL OR u.full_name ILIKE '%' || p_search || '%')
    LIMIT 300) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION web_directory(TEXT,TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION web_friends(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  RETURN jsonb_build_object(
    'friends', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'full_name') FROM (
      SELECT jsonb_build_object('friendship_id', f.id, 'user_id',
        CASE WHEN f.requester_id = v_user THEN f.receiver_id ELSE f.requester_id END,
        'full_name', u.full_name, 'role', u.role) x
      FROM friendships f
      JOIN users u ON u.id = CASE WHEN f.requester_id = v_user THEN f.receiver_id ELSE f.requester_id END
      WHERE f.status = 'ACCEPTED' AND (f.requester_id = v_user OR f.receiver_id = v_user)) s), '[]'::jsonb),
    'incoming', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at') FROM (
      SELECT jsonb_build_object('friendship_id', f.id, 'user_id', f.requester_id,
        'full_name', u.full_name, 'role', u.role, 'created_at', f.created_at) x
      FROM friendships f JOIN users u ON u.id = f.requester_id
      WHERE f.status = 'PENDING' AND f.receiver_id = v_user) s), '[]'::jsonb),
    'outgoing', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at') FROM (
      SELECT jsonb_build_object('friendship_id', f.id, 'user_id', f.receiver_id,
        'full_name', u.full_name, 'role', u.role, 'created_at', f.created_at) x
      FROM friendships f JOIN users u ON u.id = f.receiver_id
      WHERE f.status = 'PENDING' AND f.requester_id = v_user) s), '[]'::jsonb));
END $$;
GRANT EXECUTE ON FUNCTION web_friends(TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION web_friend_request(p_email TEXT, p_password TEXT, p_user UUID)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  IF p_user = v_user THEN RAISE EXCEPTION 'You cannot add yourself'; END IF;
  IF EXISTS (SELECT 1 FROM friendships
              WHERE (requester_id = v_user AND receiver_id = p_user)
                 OR (requester_id = p_user AND receiver_id = v_user)) THEN
    RAISE EXCEPTION 'A friend request already exists with this person';
  END IF;
  INSERT INTO friendships(id, requester_id, receiver_id, status)
  VALUES (uuid_generate_v4(), v_user, p_user, 'PENDING');
  RETURN jsonb_build_object('ok', TRUE);
END $$;
GRANT EXECUTE ON FUNCTION web_friend_request(TEXT,TEXT,UUID) TO anon;

CREATE OR REPLACE FUNCTION web_friend_respond(p_email TEXT, p_password TEXT, p_id UUID, p_accept BOOLEAN)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  UPDATE friendships SET status = CASE WHEN p_accept THEN 'ACCEPTED' ELSE 'DECLINED' END
   WHERE id = p_id AND receiver_id = v_user AND status = 'PENDING';
  IF NOT FOUND THEN RAISE EXCEPTION 'Friend request not found'; END IF;
  RETURN jsonb_build_object('ok', TRUE);
END $$;
GRANT EXECUTE ON FUNCTION web_friend_respond(TEXT,TEXT,UUID,BOOLEAN) TO anon;

CREATE OR REPLACE FUNCTION web_inbox(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'last_at' DESC) FROM (
    SELECT jsonb_build_object('peer_id', peer, 'full_name', u.full_name, 'role', u.role,
      'last_message', left(m.content, 90), 'last_at', m.created_at,
      'unread', (SELECT count(*) FROM messages m2
                  WHERE m2.sender_id = peer AND m2.receiver_id = v_user AND NOT COALESCE(m2.is_read, FALSE))) x
    FROM (SELECT DISTINCT ON (CASE WHEN sender_id = v_user THEN receiver_id ELSE sender_id END)
            CASE WHEN sender_id = v_user THEN receiver_id ELSE sender_id END AS peer, content, created_at
          FROM messages WHERE sender_id = v_user OR receiver_id = v_user
          ORDER BY CASE WHEN sender_id = v_user THEN receiver_id ELSE sender_id END, created_at DESC) m
    JOIN users u ON u.id = m.peer) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION web_inbox(TEXT,TEXT) TO anon;

CREATE OR REPLACE FUNCTION web_messages(p_email TEXT, p_password TEXT, p_peer UUID)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  UPDATE messages SET is_read = TRUE
   WHERE sender_id = p_peer AND receiver_id = v_user AND NOT COALESCE(is_read, FALSE);
  RETURN COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at') FROM (
    SELECT jsonb_build_object('id', m.id, 'mine', m.sender_id = v_user,
      'content', m.content, 'created_at', m.created_at, 'is_read', COALESCE(m.is_read, FALSE)) x
    FROM messages m
    WHERE (m.sender_id = v_user AND m.receiver_id = p_peer)
       OR (m.sender_id = p_peer AND m.receiver_id = v_user)
    LIMIT 500) s), '[]'::jsonb);
END $$;
GRANT EXECUTE ON FUNCTION web_messages(TEXT,TEXT,UUID) TO anon;

CREATE OR REPLACE FUNCTION web_message_send(p_email TEXT, p_password TEXT, p_peer UUID, p_content TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID; v_my_role VARCHAR; v_peer_role VARCHAR;
BEGIN
  v_user := klc_web_require(p_email, p_password,
    ARRAY['STUDENT','TEACHER','EXAM_OFFICER','PRINCIPAL_ADMIN','SUPER_ADMIN']);
  IF btrim(COALESCE(p_content, '')) = '' THEN RAISE EXCEPTION 'Message cannot be empty'; END IF;
  SELECT role INTO v_my_role FROM users WHERE id = v_user;
  SELECT role INTO v_peer_role FROM users WHERE id = p_peer AND COALESCE(is_active, TRUE);
  IF v_peer_role IS NULL THEN RAISE EXCEPTION 'Recipient not found'; END IF;
  -- Students message accepted friends or staff; staff may message anyone.
  IF v_my_role = 'STUDENT' AND v_peer_role = 'STUDENT' AND NOT EXISTS (
       SELECT 1 FROM friendships WHERE status = 'ACCEPTED'
         AND ((requester_id = v_user AND receiver_id = p_peer)
           OR (requester_id = p_peer AND receiver_id = v_user))) THEN
    RAISE EXCEPTION 'You can only message students who have accepted your friend request';
  END IF;
  INSERT INTO messages(id, sender_id, receiver_id, content)
  VALUES (uuid_generate_v4(), v_user, p_peer, btrim(p_content));
  RETURN jsonb_build_object('ok', TRUE);
END $$;
GRANT EXECUTE ON FUNCTION web_message_send(TEXT,TEXT,UUID,TEXT) TO anon;

-- ── School header on parent/student payloads (for printable report cards) ───
CREATE OR REPLACE FUNCTION parent_report(p_admission TEXT, p_pin TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_ward JSONB;
BEGIN
  SELECT jsonb_build_object('admission_no', sp.admission_no,
    'name', sp.surname || ' ' || COALESCE(sp.other_names,''),
    'class_level', sp.class_level, 'arm', sp.arm)
    INTO v_ward
  FROM student_profiles sp
  WHERE sp.admission_no = p_admission AND sp.result_pin = upper(trim(p_pin));
  IF v_ward IS NULL THEN RAISE EXCEPTION 'Invalid admission number or result PIN'; END IF;
  RETURN jsonb_build_object(
    'school', (SELECT jsonb_build_object('school_name', school_name, 'motto', motto,
                 'principal_name', principal_name, 'session_current', session_current,
                 'term_current', term_current, 'grading_scale', grading_scale)
               FROM school_profile LIMIT 1),
    'ward', v_ward,
    'results', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
      SELECT jsonb_build_object('subject_code', s.subject_code, 'class_level', e.class_level,
        'term', COALESCE(e.term,'-'), 'session', COALESCE(e.session,'-'), 'score', r.score,
        'total_questions', r.total_questions, 'percentage', r.percentage, 'grade', r.grade,
        'position', r.position, 'qr_verify_code', r.qr_verify_code, 'created_at', r.created_at) x
      FROM results r JOIN exams e ON e.id = r.exam_id JOIN subjects s ON s.id = e.subject_id
      JOIN student_profiles sp ON sp.user_id = r.student_id
      WHERE sp.admission_no = p_admission AND COALESCE(r.published, TRUE)) s), '[]'::jsonb),
    'ca', COALESCE((SELECT jsonb_agg(x) FROM (
      SELECT jsonb_build_object('subject_code', s.subject_code, 'term', cs.term,
        'session', cs.session, 'ca1', cs.ca1_score, 'ca2', cs.ca2_score,
        'exam_score', cs.exam_score, 'total', cs.total_score, 'grade', cs.grade,
        'position', cs.position) x
      FROM ca_scores cs JOIN subjects s ON s.id = cs.subject_id
      JOIN student_profiles sp ON sp.user_id = cs.student_id
      WHERE sp.admission_no = p_admission) s), '[]'::jsonb));
END $$;
GRANT EXECUTE ON FUNCTION parent_report(TEXT,TEXT) TO anon;

-- student_portal gains a `school` block (kept byte-compatible otherwise)
CREATE OR REPLACE FUNCTION student_portal(p_email TEXT, p_password TEXT)
RETURNS JSONB LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, extensions AS $$
DECLARE v_user UUID; v JSONB;
BEGIN
  v_user := klc_web_require(p_email, p_password, ARRAY['STUDENT']);
  SELECT jsonb_build_object(
    'school', (SELECT jsonb_build_object('school_name', school_name, 'motto', motto,
                 'principal_name', principal_name, 'session_current', session_current,
                 'term_current', term_current, 'grading_scale', grading_scale)
               FROM school_profile LIMIT 1),
    'profile', (SELECT jsonb_build_object('full_name', u.full_name, 'email', u.email,
        'admission_no', sp.admission_no, 'class_level', sp.class_level, 'arm', sp.arm,
        'result_pin', sp.result_pin, 'fee_status', sp.fee_status)
      FROM users u JOIN student_profiles sp ON sp.user_id = u.id WHERE u.id = v_user),
    'results', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
      SELECT jsonb_build_object('id', r.id, 'subject_code', s.subject_code,
        'term', COALESCE(e.term,'-'), 'session', COALESCE(e.session,'-'),
        'percentage', r.percentage, 'grade', r.grade, 'position', r.position,
        'qr_verify_code', r.qr_verify_code, 'created_at', r.created_at) x
      FROM results r JOIN exams e ON e.id = r.exam_id JOIN subjects s ON s.id = e.subject_id
      WHERE r.student_id = v_user AND COALESCE(r.published, TRUE)) s), '[]'::jsonb),
    'ca', COALESCE((SELECT jsonb_agg(x) FROM (
      SELECT jsonb_build_object('subject_code', s.subject_code, 'term', cs.term,
        'session', cs.session, 'ca1', cs.ca1_score, 'ca2', cs.ca2_score,
        'exam_score', cs.exam_score, 'total', cs.total_score, 'grade', cs.grade,
        'position', cs.position) x
      FROM ca_scores cs JOIN subjects s ON s.id = cs.subject_id
      WHERE cs.student_id = v_user) s), '[]'::jsonb),
    'materials', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
      SELECT jsonb_build_object('subject_code', s.subject_code, 'class_level', m.class_level,
        'title', m.title, 'description', m.description, 'file_url', m.file_url,
        'created_at', m.created_at) x
      FROM study_materials m JOIN subjects s ON s.id = m.subject_id
      JOIN student_profiles sp ON sp.user_id = v_user
      WHERE m.class_level IS NULL OR m.class_level = sp.class_level LIMIT 200) s), '[]'::jsonb),
    'announcements', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
      SELECT jsonb_build_object('title', title, 'body', body, 'created_at', created_at) x
      FROM announcements WHERE target_role IN ('ALL','STUDENT')
        AND (expires_at IS NULL OR expires_at > now()) LIMIT 50) s), '[]'::jsonb),
    'appeals', COALESCE((SELECT jsonb_agg(x ORDER BY x->>'created_at' DESC) FROM (
      SELECT jsonb_build_object('subject_code', subject_code, 'reason', reason,
        'status', status, 'created_at', created_at) x
      FROM result_appeals WHERE student_id = v_user LIMIT 100) s), '[]'::jsonb))
    INTO v;
  RETURN v;
END $$;
GRANT EXECUTE ON FUNCTION student_portal(TEXT,TEXT) TO anon;
