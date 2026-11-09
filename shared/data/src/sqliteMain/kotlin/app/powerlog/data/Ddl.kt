package app.powerlog.data

internal val ARCHIVE_DDL: List<String> =
    listOf(
        """
        CREATE TABLE app_state (
          k INTEGER PRIMARY KEY,                                                     -- always 1
          history_revision INTEGER NOT NULL, selected_bike TEXT, settings TEXT       -- settings: §3.11
        );
        """
            .trimIndent(),
        """
        CREATE TABLE ride (
          id INTEGER PRIMARY KEY AUTOINCREMENT,                                      -- local key, never reused
          uuid TEXT NOT NULL UNIQUE, recorded_by TEXT NOT NULL, example INTEGER NOT NULL, started_utc_us INTEGER NOT NULL,
          sample_hz INTEGER NOT NULL, indoor INTEGER NOT NULL, use_watch INTEGER NOT NULL, save_to_health INTEGER NOT NULL,
          record_gps INTEGER NOT NULL, distance_source TEXT NOT NULL, battery_method TEXT NOT NULL,
          phase TEXT NOT NULL, interrupted INTEGER NOT NULL,
          cp_utc_us INTEGER NOT NULL, cp_elapsed_us INTEGER NOT NULL, cp_timer_us INTEGER NOT NULL,
          end_utc_us INTEGER, end_elapsed_us INTEGER, end_timer_us INTEGER,
          health_provider TEXT, health_outcome TEXT NOT NULL, health_reason TEXT, health_workout_id TEXT,
          data_revision INTEGER NOT NULL, deleted_utc_us INTEGER
        );
        """
            .trimIndent(),
        """
        CREATE INDEX ride_history ON ride (started_utc_us DESC, uuid DESC) WHERE deleted_utc_us IS NULL;
        """
            .trimIndent(),
        """
        CREATE TABLE ride_event (
          ride_id INTEGER NOT NULL, seq INTEGER NOT NULL,                            -- seq: assigned by the writer; elapsed order
          event_id TEXT NOT NULL, kind TEXT NOT NULL,                                -- event_id: the EventId
          utc_us INTEGER NOT NULL, elapsed_us INTEGER NOT NULL,
          PRIMARY KEY (ride_id, seq), UNIQUE (ride_id, event_id)
        ) WITHOUT ROWID;
        """
            .trimIndent(),
        """
        CREATE TABLE controller_session (
          ride_id INTEGER NOT NULL, session_no INTEGER NOT NULL,                     -- 1, 2, … in connection order
          decoder TEXT NOT NULL, model TEXT NOT NULL, firmware TEXT NOT NULL, protocol TEXT NOT NULL,
          PRIMARY KEY (ride_id, session_no)
        ) WITHOUT ROWID;
        """
            .trimIndent(),
        """
        CREATE TABLE telemetry (
          ride_id INTEGER NOT NULL, elapsed_us INTEGER NOT NULL, session_no INTEGER NOT NULL,
          seq INTEGER NOT NULL,                                                      -- per controller session, in admission order
          utc_us INTEGER NOT NULL, active_interval INTEGER, run INTEGER NOT NULL,
          human_power_w INTEGER, cadence_rpm INTEGER, motor_input_power_w INTEGER, battery_voltage_v INTEGER,   -- integer quanta
          battery_current_a INTEGER, motor_current_a INTEGER, motor_rpm INTEGER, pedal_torque_nm INTEGER,
          controller_temp_c INTEGER, motor_temp_c INTEGER, consumed_ah INTEGER, consumed_wh INTEGER,
          throttle_voltage_v INTEGER, fault_code INTEGER, assist_level INTEGER, speed_raw INTEGER, race_mode INTEGER,
          PRIMARY KEY (ride_id, elapsed_us, session_no, seq)
        ) WITHOUT ROWID;
        """
            .trimIndent(),
        """
        CREATE TABLE location (
          ride_id INTEGER NOT NULL, elapsed_us INTEGER NOT NULL, seq INTEGER NOT NULL,   -- seq: per ride, in admission order
          utc_us INTEGER NOT NULL, active_interval INTEGER, run INTEGER NOT NULL, barrier INTEGER NOT NULL,
          lat REAL NOT NULL, lon REAL NOT NULL, h_acc_m REAL,
          altitude_m REAL, altitude_datum TEXT NOT NULL, v_acc_m REAL,               -- as reported, in altitude_datum
          msl_m REAL, msl_v_acc_m REAL,                                              -- above mean sea level, at capture
          speed_mps REAL, speed_acc_mps REAL, course_deg REAL, course_acc_deg REAL,
          PRIMARY KEY (ride_id, elapsed_us, seq)
        ) WITHOUT ROWID;
        """
            .trimIndent(),
        """
        CREATE TABLE health_sample (
          id INTEGER PRIMARY KEY,                                                    -- insertion order
          ride_id INTEGER NOT NULL,
          elapsed_us INTEGER NOT NULL,                                               -- the sample's end on the ride timeline; may be negative
          producer TEXT NOT NULL, sample_key TEXT NOT NULL,                          -- phone | watch; HealthSamples.sampleKey
          utc_us INTEGER NOT NULL, start_utc_us INTEGER, end_utc_us INTEGER, active_interval INTEGER,
          metric TEXT, value REAL,                                                   -- value in the metric's unit
          representation TEXT NOT NULL, sample_count INTEGER, data_origin TEXT NOT NULL,
          parent_id TEXT, member INTEGER,                                            -- the provider record id; member NULL: a plain sample
          UNIQUE (ride_id, sample_key)
        );
        """
            .trimIndent(),
        """
        CREATE INDEX health_sample_time ON health_sample (ride_id, elapsed_us);
        """
            .trimIndent(),
        """
        CREATE INDEX health_sample_metric ON health_sample (ride_id, metric, elapsed_us) WHERE metric IS NOT NULL;
        """
            .trimIndent(),
        """
        CREATE TABLE health_work (
          ride_id INTEGER NOT NULL, task TEXT NOT NULL,                              -- publish | readback
          scope TEXT NOT NULL,                                                       -- HealthComponent or HealthMetric
          state TEXT NOT NULL, attempts INTEGER NOT NULL, cursor TEXT, due_utc_us INTEGER, reason TEXT,
          PRIMARY KEY (ride_id, task, scope)
        ) WITHOUT ROWID;
        """
            .trimIndent(),
        """
        CREATE INDEX health_work_due ON health_work (due_utc_us) WHERE due_utc_us IS NOT NULL;
        """
            .trimIndent(),
        """
        CREATE TABLE known_controller (
          bike_id TEXT NOT NULL PRIMARY KEY,                                         -- as the OS gives it
          decoder TEXT NOT NULL, model TEXT NOT NULL, firmware TEXT NOT NULL, protocol TEXT NOT NULL
        ) WITHOUT ROWID;
        """
            .trimIndent(),
    )

internal val CACHE_DDL: List<String> =
    listOf(
        """
        CREATE TABLE cache_meta (k INTEGER PRIMARY KEY, layout INTEGER NOT NULL, rules_version INTEGER NOT NULL);   -- one row: k 1, CACHE_LAYOUT, RULES_VERSION
        """
            .trimIndent(),
        """
        CREATE TABLE derived (
          ride TEXT NOT NULL,                                                        -- the ride UUID
          family TEXT NOT NULL, variant TEXT NOT NULL, part INTEGER NOT NULL,
          input_revision INTEGER NOT NULL,                                           -- the data_revision the value came from; 0: a final tile
          value BLOB NOT NULL,                                                       -- CacheCodec
          PRIMARY KEY (ride, family, variant, part)
        ) WITHOUT ROWID;
        """
            .trimIndent(),
    )
