CREATE TABLE IF NOT EXISTS activity (
  installation TEXT NOT NULL,
  day TEXT NOT NULL,
  version TEXT NOT NULL,
  plays INTEGER NOT NULL CHECK (plays BETWEEN 0 AND 10000),
  received_at TEXT NOT NULL,
  PRIMARY KEY (installation, day, version)
);
CREATE INDEX IF NOT EXISTS activity_day ON activity(day, installation);
CREATE INDEX IF NOT EXISTS activity_version ON activity(version, installation);
-- UTC daily totals cannot be shifted accurately after collection. Keep the
-- legacy table and collect new IST days separately, without rewriting history.
CREATE TABLE IF NOT EXISTS activity_ist (
  installation TEXT NOT NULL,
  day TEXT NOT NULL,
  version TEXT NOT NULL,
  plays INTEGER NOT NULL CHECK (plays BETWEEN 0 AND 10000),
  received_at TEXT NOT NULL,
  PRIMARY KEY (installation, day, version)
);
CREATE INDEX IF NOT EXISTS activity_ist_day ON activity_ist(day, installation);
CREATE INDEX IF NOT EXISTS activity_ist_version ON activity_ist(version, installation);
CREATE TABLE IF NOT EXISTS installation_versions (
  installation TEXT PRIMARY KEY,
  version TEXT NOT NULL,
  revision INTEGER NOT NULL CHECK (revision >= 1),
  received_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS downloads (
  browser TEXT NOT NULL,
  release TEXT NOT NULL,
  first_requested_at TEXT NOT NULL,
  PRIMARY KEY (browser, release)
);

-- Public community addon catalog; only the authenticated owner can modify it.
CREATE TABLE IF NOT EXISTS addon_catalog (
  id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, payload TEXT NOT NULL
);

-- Version changes, one row per move, recorded by a trigger when an installation's
-- current version changes. The version breakdown above already moves an
-- installation's count from its old version to its new one; this counts those
-- moves, so an update is never mistaken for a new installation. A retry at the
-- same revision does not update the row, so it records nothing.
CREATE TABLE IF NOT EXISTS version_transitions (
  installation TEXT NOT NULL,
  from_version TEXT NOT NULL,
  to_version TEXT NOT NULL,
  changed_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS version_transitions_to ON version_transitions(to_version);
CREATE INDEX IF NOT EXISTS version_transitions_at ON version_transitions(changed_at);
CREATE TRIGGER IF NOT EXISTS record_version_change
AFTER UPDATE OF version ON installation_versions
FOR EACH ROW WHEN old.version <> new.version
BEGIN
  INSERT INTO version_transitions(installation, from_version, to_version, changed_at)
  VALUES (new.installation, old.version, new.version, new.received_at);
END;
