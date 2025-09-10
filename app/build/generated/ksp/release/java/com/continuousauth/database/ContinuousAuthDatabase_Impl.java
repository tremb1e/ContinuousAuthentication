package com.continuousauth.database;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class ContinuousAuthDatabase_Impl extends ContinuousAuthDatabase {
  private volatile BatchMetadataDao _batchMetadataDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(1) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `batch_metadata` (`packetId` TEXT NOT NULL, `filePath` TEXT NOT NULL, `status` TEXT NOT NULL, `createdTime` INTEGER NOT NULL, `uploadTime` INTEGER, `ackTime` INTEGER, `fileSize` INTEGER NOT NULL, `sampleCount` INTEGER NOT NULL, `transmissionMode` TEXT NOT NULL, `ntpOffset` INTEGER, `baseWallMs` INTEGER NOT NULL, `deviceUptimeNs` INTEGER NOT NULL, `retryCount` INTEGER NOT NULL, `lastError` TEXT, `sequenceNumber` INTEGER, `userId` TEXT, `sessionId` TEXT, `deviceId` TEXT NOT NULL, `sha256` TEXT, PRIMARY KEY(`packetId`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '46c317f07ec17c779fb2cbe4e49e053e')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `batch_metadata`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsBatchMetadata = new HashMap<String, TableInfo.Column>(19);
        _columnsBatchMetadata.put("packetId", new TableInfo.Column("packetId", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("filePath", new TableInfo.Column("filePath", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("status", new TableInfo.Column("status", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("createdTime", new TableInfo.Column("createdTime", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("uploadTime", new TableInfo.Column("uploadTime", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("ackTime", new TableInfo.Column("ackTime", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("fileSize", new TableInfo.Column("fileSize", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("sampleCount", new TableInfo.Column("sampleCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("transmissionMode", new TableInfo.Column("transmissionMode", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("ntpOffset", new TableInfo.Column("ntpOffset", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("baseWallMs", new TableInfo.Column("baseWallMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("deviceUptimeNs", new TableInfo.Column("deviceUptimeNs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("retryCount", new TableInfo.Column("retryCount", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("lastError", new TableInfo.Column("lastError", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("sequenceNumber", new TableInfo.Column("sequenceNumber", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("userId", new TableInfo.Column("userId", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("sessionId", new TableInfo.Column("sessionId", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("deviceId", new TableInfo.Column("deviceId", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsBatchMetadata.put("sha256", new TableInfo.Column("sha256", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysBatchMetadata = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesBatchMetadata = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoBatchMetadata = new TableInfo("batch_metadata", _columnsBatchMetadata, _foreignKeysBatchMetadata, _indicesBatchMetadata);
        final TableInfo _existingBatchMetadata = TableInfo.read(db, "batch_metadata");
        if (!_infoBatchMetadata.equals(_existingBatchMetadata)) {
          return new RoomOpenHelper.ValidationResult(false, "batch_metadata(com.continuousauth.database.BatchMetadata).\n"
                  + " Expected:\n" + _infoBatchMetadata + "\n"
                  + " Found:\n" + _existingBatchMetadata);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "46c317f07ec17c779fb2cbe4e49e053e", "28efb2375c6cb2eec27dda8f2a54b215");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "batch_metadata");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `batch_metadata`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(BatchMetadataDao.class, BatchMetadataDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public BatchMetadataDao batchMetadataDao() {
    if (_batchMetadataDao != null) {
      return _batchMetadataDao;
    } else {
      synchronized(this) {
        if(_batchMetadataDao == null) {
          _batchMetadataDao = new BatchMetadataDao_Impl(this);
        }
        return _batchMetadataDao;
      }
    }
  }
}
