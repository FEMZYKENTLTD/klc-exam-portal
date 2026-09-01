-- ============================================================================
-- KLC CBT Suite v1.0 - SOCIAL MODULE MIGRATION (Profile / Friends / Messages)
-- Run this in the Supabase SQL editor if the app's database role is not
-- allowed to run CREATE TABLE at startup (DatabaseInitializer also creates
-- these idempotently when it connects as a superuser role).
--
-- IMPORTANT: friendships.receiver_id is the LIVE column name. Do NOT rename
-- it to addressee_id - FriendsController and MessagesController both query
-- receiver_id (hotfix A5).
-- ============================================================================

-- ── user_profiles ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_profiles (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id       UUID UNIQUE REFERENCES users(id) ON DELETE CASCADE,
  photo_url     TEXT,
  bio           TEXT,
  date_of_birth DATE,
  address       TEXT,
  updated_at    TIMESTAMP DEFAULT now()
);

-- ── friendships ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS friendships (
  id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  requester_id UUID REFERENCES users(id) ON DELETE CASCADE,
  receiver_id  UUID REFERENCES users(id) ON DELETE CASCADE,
  status       VARCHAR(20) DEFAULT 'PENDING'
               CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED')),
  created_at   TIMESTAMP DEFAULT now()
);

-- Legacy offline caches may have created friendships with addressee_id.
-- Migrate any data across, then drop the legacy column.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'friendships' AND column_name = 'addressee_id'
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'friendships' AND column_name = 'receiver_id'
  ) THEN
    ALTER TABLE friendships RENAME COLUMN addressee_id TO receiver_id;
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_friendships_requester ON friendships(requester_id);
CREATE INDEX IF NOT EXISTS idx_friendships_receiver  ON friendships(receiver_id);

-- ── messages ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS messages (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  sender_id   UUID REFERENCES users(id) ON DELETE CASCADE,
  receiver_id UUID REFERENCES users(id) ON DELETE CASCADE,
  content     TEXT,
  is_read     BOOLEAN DEFAULT FALSE,
  created_at  TIMESTAMP DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_messages_sender   ON messages(sender_id);
CREATE INDEX IF NOT EXISTS idx_messages_receiver ON messages(receiver_id);

-- ── Row Level Security (defense in depth; the desktop app connects with the
--    service role / postgres role which bypasses RLS, but these policies
--    protect any web/expose endpoint usage) ─────────────────────────────────
ALTER TABLE user_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE friendships  ENABLE ROW LEVEL SECURITY;
ALTER TABLE messages     ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "service role full access user_profiles" ON user_profiles;
CREATE POLICY "service role full access user_profiles" ON user_profiles
  FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "service role full access friendships" ON friendships;
CREATE POLICY "service role full access friendships" ON friendships
  FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "service role full access messages" ON messages;
CREATE POLICY "service role full access messages" ON messages
  FOR ALL TO service_role USING (true) WITH CHECK (true);
