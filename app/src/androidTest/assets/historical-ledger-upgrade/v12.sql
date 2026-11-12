-- schema 12, DDL from historical writer 61e9ed140e6b2ac2431597939529ad5721183029.
CREATE TABLE quest_tracking (profile TEXT PRIMARY KEY NOT NULL, source TEXT NOT NULL, complete_since INTEGER)
CREATE TABLE quest_events (id TEXT PRIMARY KEY NOT NULL, profile TEXT NOT NULL, source TEXT NOT NULL, action TEXT NOT NULL, cause_request TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(profile,source,action,cause_request))
CREATE TABLE quest_claims (request_id TEXT PRIMARY KEY NOT NULL, profile TEXT NOT NULL, source TEXT NOT NULL, quest_id TEXT NOT NULL, period_start INTEGER NOT NULL, period_end INTEGER NOT NULL, command TEXT NOT NULL, progress TEXT NOT NULL, balance_before INTEGER NOT NULL, event_cutoff INTEGER NOT NULL, complete_since INTEGER NOT NULL, result TEXT NOT NULL, UNIQUE(profile,source,quest_id,period_start))
ALTER TABLE delivery_requests ADD COLUMN host_command TEXT
ALTER TABLE delivery_changes ADD COLUMN host_command TEXT
ALTER TABLE delivery_outcomes ADD COLUMN host_command TEXT
ALTER TABLE delivery_hold_outcomes ADD COLUMN host_command TEXT
