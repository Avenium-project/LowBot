package io.lowbot.core;

/** Schema v1 of the on-device database (mirrors server/app/v2/schema.py). */
final class Schema {
    private Schema() {}

    static final String[] V1 = {
        "CREATE TABLE bots (id TEXT PRIMARY KEY, name TEXT NOT NULL, handle TEXT NOT NULL UNIQUE, label TEXT NOT NULL DEFAULT '', "
            + "avatar TEXT NOT NULL DEFAULT '', role_description TEXT NOT NULL DEFAULT '', instructions TEXT NOT NULL DEFAULT '', "
            + "provider_profile_id TEXT, model TEXT, tools_json TEXT NOT NULL DEFAULT '[]', policy_json TEXT NOT NULL DEFAULT '[]', "
            + "budget_json TEXT NOT NULL DEFAULT '{}', org_role TEXT, reports_to TEXT, can_create_bots INTEGER NOT NULL DEFAULT 0, "
            + "created_by_bot_id TEXT, team_memory_access INTEGER NOT NULL DEFAULT 0, computer_mode TEXT NOT NULL DEFAULT 'shared', "
            + "notify INTEGER NOT NULL DEFAULT 1, pinned INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0, "
            + "paused INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        "CREATE TABLE conversations (id TEXT PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL DEFAULT '', default_bot_id TEXT, "
            + "archived INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0, "
            + "last_read_seq INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        "CREATE TABLE memberships (conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, member_type TEXT NOT NULL, "
            + "member_id TEXT NOT NULL, joined_at TEXT NOT NULL, PRIMARY KEY (conversation_id, member_type, member_id))",
        "CREATE TABLE messages (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, conversation_id TEXT NOT NULL "
            + "REFERENCES conversations(id) ON DELETE CASCADE, author_type TEXT NOT NULL, author_id TEXT, text TEXT NOT NULL DEFAULT '', "
            + "mentions_json TEXT NOT NULL DEFAULT '[]', thread_root_id TEXT, task_id TEXT, client_msg_id TEXT, "
            + "attachments_json TEXT NOT NULL DEFAULT '[]', meta_json TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL)",
        "CREATE UNIQUE INDEX idx_messages_client ON messages(conversation_id, client_msg_id)",
        "CREATE INDEX idx_messages_conv ON messages(conversation_id, seq)",
        "CREATE TABLE tasks (id TEXT PRIMARY KEY, conversation_id TEXT, bot_id TEXT NOT NULL, requester_type TEXT NOT NULL, requester_id TEXT, "
            + "parent_task_id TEXT, root_task_id TEXT, correlation_id TEXT NOT NULL, depth INTEGER NOT NULL DEFAULT 0, title TEXT NOT NULL DEFAULT '', "
            + "instructions TEXT NOT NULL DEFAULT '', expected_output TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, priority INTEGER NOT NULL DEFAULT 50, "
            + "result_text TEXT, error TEXT, skill_id TEXT, skill_version INTEGER, source_message_id TEXT, routine_run_id TEXT, "
            + "unread INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, completed_at TEXT)",
        "CREATE INDEX idx_tasks_bot ON tasks(bot_id, status)",
        "CREATE INDEX idx_tasks_corr ON tasks(correlation_id)",
        "CREATE TABLE runs (id TEXT PRIMARY KEY, task_id TEXT NOT NULL REFERENCES tasks(id) ON DELETE CASCADE, bot_id TEXT NOT NULL, "
            + "status TEXT NOT NULL, priority INTEGER NOT NULL DEFAULT 50, attempt INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL DEFAULT 4, "
            + "not_before TEXT, owner TEXT, control TEXT NOT NULL DEFAULT '', waiting_json TEXT NOT NULL DEFAULT '{}', step_count INTEGER NOT NULL DEFAULT 0, "
            + "max_steps INTEGER NOT NULL, deadline_at TEXT, created_at TEXT NOT NULL, started_at TEXT, updated_at TEXT NOT NULL, finished_at TEXT, error TEXT)",
        "CREATE INDEX idx_runs_claim ON runs(status, priority, created_at)",
        "CREATE TABLE run_steps (id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE, seq INTEGER NOT NULL, "
            + "kind TEXT NOT NULL, status TEXT NOT NULL, tool_name TEXT, call_id TEXT, input_json TEXT NOT NULL DEFAULT '{}', "
            + "output_json TEXT NOT NULL DEFAULT '{}', error TEXT, idempotency_key TEXT, approval_id TEXT, created_at TEXT NOT NULL, "
            + "updated_at TEXT NOT NULL, UNIQUE (run_id, seq))",
        "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, conversation_id TEXT, task_id TEXT, run_id TEXT, "
            + "bot_id TEXT, payload_json TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL)",
        "CREATE TABLE handoffs (id TEXT PRIMARY KEY, from_task_id TEXT NOT NULL, from_bot_id TEXT NOT NULL, to_task_id TEXT NOT NULL, "
            + "to_bot_id TEXT NOT NULL, wait INTEGER NOT NULL DEFAULT 1, step_id TEXT, status TEXT NOT NULL, created_at TEXT NOT NULL, completed_at TEXT)",
        "CREATE TABLE approvals (id TEXT PRIMARY KEY, run_id TEXT NOT NULL, step_id TEXT NOT NULL, task_id TEXT NOT NULL, bot_id TEXT NOT NULL, "
            + "conversation_id TEXT, tool TEXT NOT NULL, args_hash TEXT NOT NULL, display_json TEXT NOT NULL DEFAULT '{}', summary TEXT NOT NULL DEFAULT '', "
            + "effect TEXT NOT NULL DEFAULT '', target TEXT NOT NULL DEFAULT '', status TEXT NOT NULL, approver_user_id TEXT NOT NULL, "
            + "decided_by TEXT, decided_at TEXT, expires_at TEXT NOT NULL, consumed_at TEXT, review_json TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL)",
        "CREATE TABLE operations (idempotency_key TEXT PRIMARY KEY, run_id TEXT NOT NULL, step_id TEXT NOT NULL, tool TEXT NOT NULL, "
            + "status TEXT NOT NULL, request_json TEXT NOT NULL DEFAULT '{}', result_json TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        "CREATE TABLE routines (id TEXT PRIMARY KEY, bot_id TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, schedule_json TEXT NOT NULL DEFAULT '{}', "
            + "timezone TEXT NOT NULL, prompt TEXT NOT NULL, conversation_id TEXT, enabled INTEGER NOT NULL DEFAULT 1, overlap_policy TEXT NOT NULL DEFAULT 'skip', "
            + "catchup_policy TEXT NOT NULL DEFAULT 'latest', next_run_at TEXT, last_run_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        "CREATE TABLE routine_runs (id TEXT PRIMARY KEY, routine_id TEXT NOT NULL REFERENCES routines(id) ON DELETE CASCADE, dedupe_key TEXT NOT NULL, "
            + "scheduled_for TEXT, trigger TEXT NOT NULL, status TEXT NOT NULL, task_id TEXT, input_json TEXT NOT NULL DEFAULT '{}', error TEXT, "
            + "created_at TEXT NOT NULL, UNIQUE (routine_id, dedupe_key))",
        "CREATE TABLE memories (id TEXT PRIMARY KEY, scope TEXT NOT NULL, bot_id TEXT, content TEXT NOT NULL, source TEXT NOT NULL DEFAULT '', "
            + "created_by TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT)",
        "CREATE VIRTUAL TABLE memories_fts USING fts4(content, memory_id, tokenize=unicode61)",
        "CREATE TABLE skills (id TEXT PRIMARY KEY, slug TEXT NOT NULL UNIQUE, name TEXT NOT NULL, current_version INTEGER NOT NULL DEFAULT 1, "
            + "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        "CREATE TABLE skill_versions (skill_id TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE, version INTEGER NOT NULL, "
            + "instructions TEXT NOT NULL, inputs_json TEXT NOT NULL DEFAULT '[]', tools_json TEXT NOT NULL DEFAULT '[]', "
            + "completion_criteria TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT 'manual', created_at TEXT NOT NULL, PRIMARY KEY (skill_id, version))",
        "CREATE TABLE bot_skills (bot_id TEXT NOT NULL, skill_id TEXT NOT NULL, PRIMARY KEY (bot_id, skill_id))",
        "CREATE TABLE provider_profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, base_url TEXT NOT NULL DEFAULT '', "
            + "api_key_secret_id TEXT, default_model TEXT NOT NULL DEFAULT '', models_json TEXT NOT NULL DEFAULT '[]', "
            + "capabilities_json TEXT NOT NULL DEFAULT '{}', prices_json TEXT NOT NULL DEFAULT '{}', last_test_json TEXT NOT NULL DEFAULT '{}', "
            + "created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        "CREATE TABLE tool_connections (id TEXT PRIMARY KEY, name TEXT NOT NULL UNIQUE, transport TEXT NOT NULL, url TEXT, auth_secret_id TEXT, "
            + "enabled INTEGER NOT NULL DEFAULT 1, timeout_s REAL NOT NULL DEFAULT 30, tools_cache_json TEXT NOT NULL DEFAULT '[]', "
            + "last_status TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        "CREATE TABLE secret_references (id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'generic', description TEXT NOT NULL DEFAULT '', "
            + "ciphertext TEXT NOT NULL, created_at TEXT NOT NULL, last_used_at TEXT)",
        "CREATE TABLE artifacts (id TEXT PRIMARY KEY, task_id TEXT, run_id TEXT, bot_id TEXT, conversation_id TEXT, name TEXT NOT NULL, mime TEXT NOT NULL, "
            + "size INTEGER NOT NULL, sha256 TEXT NOT NULL, version INTEGER NOT NULL DEFAULT 1, storage_path TEXT NOT NULL, created_at TEXT NOT NULL)",
        "CREATE TABLE usage_entries (id TEXT PRIMARY KEY, run_id TEXT, bot_id TEXT, kind TEXT NOT NULL, status TEXT NOT NULL, provider TEXT, model TEXT, "
            + "input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0, cost_estimated REAL NOT NULL DEFAULT 0, "
            + "cost_confirmed REAL, created_at TEXT NOT NULL)",
        "CREATE TABLE computer_sessions (id TEXT PRIMARY KEY, bot_id TEXT NOT NULL UNIQUE, controller TEXT NOT NULL, lock_version INTEGER NOT NULL DEFAULT 0, "
            + "status TEXT NOT NULL DEFAULT 'idle', url TEXT NOT NULL DEFAULT '', recording_json TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
        "CREATE TABLE notifications (id TEXT PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL DEFAULT '', task_id TEXT, "
            + "approval_id TEXT, conversation_id TEXT, bot_id TEXT, read_at TEXT, push_status TEXT NOT NULL DEFAULT 'pending', created_at TEXT NOT NULL)",
        "CREATE TABLE policy_rules (id TEXT PRIMARY KEY, bot_id TEXT, tool_pattern TEXT NOT NULL, effect TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'user', "
            + "created_at TEXT NOT NULL)",
        "CREATE TABLE audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, actor_type TEXT NOT NULL, actor_id TEXT, action TEXT NOT NULL, task_id TEXT, "
            + "run_id TEXT, approval_id TEXT, decision TEXT, outcome TEXT, summary_json TEXT NOT NULL DEFAULT '{}', created_at TEXT NOT NULL)",
        "CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
    };

    /** v2: a conversation can be bound to a project folder (workspace/<project>/AGENTS.md). */
    static final String[] V2 = {
        "ALTER TABLE conversations ADD COLUMN project TEXT",
    };
}
