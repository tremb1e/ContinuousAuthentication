package com.continuousauth.database;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.room.util.StringUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.StringBuilder;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class BatchMetadataDao_Impl implements BatchMetadataDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<BatchMetadata> __insertionAdapterOfBatchMetadata;

  private final Converters __converters = new Converters();

  private final EntityDeletionOrUpdateAdapter<BatchMetadata> __deletionAdapterOfBatchMetadata;

  private final EntityDeletionOrUpdateAdapter<BatchMetadata> __updateAdapterOfBatchMetadata;

  private final SharedSQLiteStatement __preparedStmtOfUpdateStatus;

  private final SharedSQLiteStatement __preparedStmtOfUpdateUploadStatus;

  private final SharedSQLiteStatement __preparedStmtOfUpdateAckStatus;

  private final SharedSQLiteStatement __preparedStmtOfUpdateRetryInfo;

  private final SharedSQLiteStatement __preparedStmtOfDeleteById;

  private final SharedSQLiteStatement __preparedStmtOfDeleteByStatus;

  private final SharedSQLiteStatement __preparedStmtOfDeleteOldest;

  private final SharedSQLiteStatement __preparedStmtOfClearAll;

  public BatchMetadataDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfBatchMetadata = new EntityInsertionAdapter<BatchMetadata>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `batch_metadata` (`packetId`,`filePath`,`status`,`createdTime`,`uploadTime`,`ackTime`,`fileSize`,`sampleCount`,`transmissionMode`,`ntpOffset`,`baseWallMs`,`deviceUptimeNs`,`retryCount`,`lastError`,`sequenceNumber`,`userId`,`sessionId`,`deviceId`,`sha256`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final BatchMetadata entity) {
        statement.bindString(1, entity.getPacketId());
        statement.bindString(2, entity.getFilePath());
        final String _tmp = __converters.fromBatchStatus(entity.getStatus());
        statement.bindString(3, _tmp);
        statement.bindLong(4, entity.getCreatedTime());
        if (entity.getUploadTime() == null) {
          statement.bindNull(5);
        } else {
          statement.bindLong(5, entity.getUploadTime());
        }
        if (entity.getAckTime() == null) {
          statement.bindNull(6);
        } else {
          statement.bindLong(6, entity.getAckTime());
        }
        statement.bindLong(7, entity.getFileSize());
        statement.bindLong(8, entity.getSampleCount());
        statement.bindString(9, entity.getTransmissionMode());
        if (entity.getNtpOffset() == null) {
          statement.bindNull(10);
        } else {
          statement.bindLong(10, entity.getNtpOffset());
        }
        statement.bindLong(11, entity.getBaseWallMs());
        statement.bindLong(12, entity.getDeviceUptimeNs());
        statement.bindLong(13, entity.getRetryCount());
        if (entity.getLastError() == null) {
          statement.bindNull(14);
        } else {
          statement.bindString(14, entity.getLastError());
        }
        if (entity.getSequenceNumber() == null) {
          statement.bindNull(15);
        } else {
          statement.bindLong(15, entity.getSequenceNumber());
        }
        if (entity.getUserId() == null) {
          statement.bindNull(16);
        } else {
          statement.bindString(16, entity.getUserId());
        }
        if (entity.getSessionId() == null) {
          statement.bindNull(17);
        } else {
          statement.bindString(17, entity.getSessionId());
        }
        statement.bindString(18, entity.getDeviceId());
        if (entity.getSha256() == null) {
          statement.bindNull(19);
        } else {
          statement.bindString(19, entity.getSha256());
        }
      }
    };
    this.__deletionAdapterOfBatchMetadata = new EntityDeletionOrUpdateAdapter<BatchMetadata>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `batch_metadata` WHERE `packetId` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final BatchMetadata entity) {
        statement.bindString(1, entity.getPacketId());
      }
    };
    this.__updateAdapterOfBatchMetadata = new EntityDeletionOrUpdateAdapter<BatchMetadata>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "UPDATE OR ABORT `batch_metadata` SET `packetId` = ?,`filePath` = ?,`status` = ?,`createdTime` = ?,`uploadTime` = ?,`ackTime` = ?,`fileSize` = ?,`sampleCount` = ?,`transmissionMode` = ?,`ntpOffset` = ?,`baseWallMs` = ?,`deviceUptimeNs` = ?,`retryCount` = ?,`lastError` = ?,`sequenceNumber` = ?,`userId` = ?,`sessionId` = ?,`deviceId` = ?,`sha256` = ? WHERE `packetId` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final BatchMetadata entity) {
        statement.bindString(1, entity.getPacketId());
        statement.bindString(2, entity.getFilePath());
        final String _tmp = __converters.fromBatchStatus(entity.getStatus());
        statement.bindString(3, _tmp);
        statement.bindLong(4, entity.getCreatedTime());
        if (entity.getUploadTime() == null) {
          statement.bindNull(5);
        } else {
          statement.bindLong(5, entity.getUploadTime());
        }
        if (entity.getAckTime() == null) {
          statement.bindNull(6);
        } else {
          statement.bindLong(6, entity.getAckTime());
        }
        statement.bindLong(7, entity.getFileSize());
        statement.bindLong(8, entity.getSampleCount());
        statement.bindString(9, entity.getTransmissionMode());
        if (entity.getNtpOffset() == null) {
          statement.bindNull(10);
        } else {
          statement.bindLong(10, entity.getNtpOffset());
        }
        statement.bindLong(11, entity.getBaseWallMs());
        statement.bindLong(12, entity.getDeviceUptimeNs());
        statement.bindLong(13, entity.getRetryCount());
        if (entity.getLastError() == null) {
          statement.bindNull(14);
        } else {
          statement.bindString(14, entity.getLastError());
        }
        if (entity.getSequenceNumber() == null) {
          statement.bindNull(15);
        } else {
          statement.bindLong(15, entity.getSequenceNumber());
        }
        if (entity.getUserId() == null) {
          statement.bindNull(16);
        } else {
          statement.bindString(16, entity.getUserId());
        }
        if (entity.getSessionId() == null) {
          statement.bindNull(17);
        } else {
          statement.bindString(17, entity.getSessionId());
        }
        statement.bindString(18, entity.getDeviceId());
        if (entity.getSha256() == null) {
          statement.bindNull(19);
        } else {
          statement.bindString(19, entity.getSha256());
        }
        statement.bindString(20, entity.getPacketId());
      }
    };
    this.__preparedStmtOfUpdateStatus = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE batch_metadata SET status = ? WHERE packetId = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateUploadStatus = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE batch_metadata SET status = ?, uploadTime = ? WHERE packetId = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateAckStatus = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE batch_metadata SET status = ?, ackTime = ? WHERE packetId = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdateRetryInfo = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE batch_metadata SET retryCount = retryCount + 1, lastError = ? WHERE packetId = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteById = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM batch_metadata WHERE packetId = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteByStatus = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM batch_metadata WHERE status = ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteOldest = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM batch_metadata WHERE packetId IN (SELECT packetId FROM batch_metadata ORDER BY createdTime ASC LIMIT ?)";
        return _query;
      }
    };
    this.__preparedStmtOfClearAll = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM batch_metadata";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final BatchMetadata batch, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfBatchMetadata.insert(batch);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object insertAll(final BatchMetadata[] batches,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfBatchMetadata.insert(batches);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object delete(final BatchMetadata batch, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __deletionAdapterOfBatchMetadata.handle(batch);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object update(final BatchMetadata batch, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __updateAdapterOfBatchMetadata.handle(batch);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object updateStatus(final String packetId, final BatchStatus status,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateStatus.acquire();
        int _argIndex = 1;
        final String _tmp = __converters.fromBatchStatus(status);
        _stmt.bindString(_argIndex, _tmp);
        _argIndex = 2;
        _stmt.bindString(_argIndex, packetId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateStatus.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateUploadStatus(final String packetId, final BatchStatus status,
      final long uploadTime, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateUploadStatus.acquire();
        int _argIndex = 1;
        final String _tmp = __converters.fromBatchStatus(status);
        _stmt.bindString(_argIndex, _tmp);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, uploadTime);
        _argIndex = 3;
        _stmt.bindString(_argIndex, packetId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateUploadStatus.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateAckStatus(final String packetId, final BatchStatus status, final long ackTime,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateAckStatus.acquire();
        int _argIndex = 1;
        final String _tmp = __converters.fromBatchStatus(status);
        _stmt.bindString(_argIndex, _tmp);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, ackTime);
        _argIndex = 3;
        _stmt.bindString(_argIndex, packetId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateAckStatus.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updateRetryInfo(final String packetId, final String error,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateRetryInfo.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, error);
        _argIndex = 2;
        _stmt.bindString(_argIndex, packetId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateRetryInfo.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteById(final String packetId, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteById.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, packetId);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteById.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteByStatus(final BatchStatus status,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteByStatus.acquire();
        int _argIndex = 1;
        final String _tmp = __converters.fromBatchStatus(status);
        _stmt.bindString(_argIndex, _tmp);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteByStatus.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteOldest(final int count, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteOldest.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, count);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteOldest.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object clearAll(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClearAll.acquire();
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfClearAll.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object getPendingBatches(final List<? extends BatchStatus> statuses,
      final Continuation<? super List<BatchMetadata>> $completion) {
    final StringBuilder _stringBuilder = StringUtil.newStringBuilder();
    _stringBuilder.append("SELECT * FROM batch_metadata WHERE status IN (");
    final int _inputSize = statuses.size();
    StringUtil.appendPlaceholders(_stringBuilder, _inputSize);
    _stringBuilder.append(") ORDER BY createdTime ASC");
    final String _sql = _stringBuilder.toString();
    final int _argCount = 0 + _inputSize;
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, _argCount);
    int _argIndex = 1;
    for (BatchStatus _item : statuses) {
      final String _tmp = __converters.fromBatchStatus(_item);
      _statement.bindString(_argIndex, _tmp);
      _argIndex++;
    }
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<BatchMetadata>>() {
      @Override
      @NonNull
      public List<BatchMetadata> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfPacketId = CursorUtil.getColumnIndexOrThrow(_cursor, "packetId");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfCreatedTime = CursorUtil.getColumnIndexOrThrow(_cursor, "createdTime");
          final int _cursorIndexOfUploadTime = CursorUtil.getColumnIndexOrThrow(_cursor, "uploadTime");
          final int _cursorIndexOfAckTime = CursorUtil.getColumnIndexOrThrow(_cursor, "ackTime");
          final int _cursorIndexOfFileSize = CursorUtil.getColumnIndexOrThrow(_cursor, "fileSize");
          final int _cursorIndexOfSampleCount = CursorUtil.getColumnIndexOrThrow(_cursor, "sampleCount");
          final int _cursorIndexOfTransmissionMode = CursorUtil.getColumnIndexOrThrow(_cursor, "transmissionMode");
          final int _cursorIndexOfNtpOffset = CursorUtil.getColumnIndexOrThrow(_cursor, "ntpOffset");
          final int _cursorIndexOfBaseWallMs = CursorUtil.getColumnIndexOrThrow(_cursor, "baseWallMs");
          final int _cursorIndexOfDeviceUptimeNs = CursorUtil.getColumnIndexOrThrow(_cursor, "deviceUptimeNs");
          final int _cursorIndexOfRetryCount = CursorUtil.getColumnIndexOrThrow(_cursor, "retryCount");
          final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "lastError");
          final int _cursorIndexOfSequenceNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "sequenceNumber");
          final int _cursorIndexOfUserId = CursorUtil.getColumnIndexOrThrow(_cursor, "userId");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final int _cursorIndexOfDeviceId = CursorUtil.getColumnIndexOrThrow(_cursor, "deviceId");
          final int _cursorIndexOfSha256 = CursorUtil.getColumnIndexOrThrow(_cursor, "sha256");
          final List<BatchMetadata> _result = new ArrayList<BatchMetadata>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final BatchMetadata _item_1;
            final String _tmpPacketId;
            _tmpPacketId = _cursor.getString(_cursorIndexOfPacketId);
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final BatchStatus _tmpStatus;
            final String _tmp_1;
            _tmp_1 = _cursor.getString(_cursorIndexOfStatus);
            _tmpStatus = __converters.toBatchStatus(_tmp_1);
            final long _tmpCreatedTime;
            _tmpCreatedTime = _cursor.getLong(_cursorIndexOfCreatedTime);
            final Long _tmpUploadTime;
            if (_cursor.isNull(_cursorIndexOfUploadTime)) {
              _tmpUploadTime = null;
            } else {
              _tmpUploadTime = _cursor.getLong(_cursorIndexOfUploadTime);
            }
            final Long _tmpAckTime;
            if (_cursor.isNull(_cursorIndexOfAckTime)) {
              _tmpAckTime = null;
            } else {
              _tmpAckTime = _cursor.getLong(_cursorIndexOfAckTime);
            }
            final long _tmpFileSize;
            _tmpFileSize = _cursor.getLong(_cursorIndexOfFileSize);
            final int _tmpSampleCount;
            _tmpSampleCount = _cursor.getInt(_cursorIndexOfSampleCount);
            final String _tmpTransmissionMode;
            _tmpTransmissionMode = _cursor.getString(_cursorIndexOfTransmissionMode);
            final Long _tmpNtpOffset;
            if (_cursor.isNull(_cursorIndexOfNtpOffset)) {
              _tmpNtpOffset = null;
            } else {
              _tmpNtpOffset = _cursor.getLong(_cursorIndexOfNtpOffset);
            }
            final long _tmpBaseWallMs;
            _tmpBaseWallMs = _cursor.getLong(_cursorIndexOfBaseWallMs);
            final long _tmpDeviceUptimeNs;
            _tmpDeviceUptimeNs = _cursor.getLong(_cursorIndexOfDeviceUptimeNs);
            final int _tmpRetryCount;
            _tmpRetryCount = _cursor.getInt(_cursorIndexOfRetryCount);
            final String _tmpLastError;
            if (_cursor.isNull(_cursorIndexOfLastError)) {
              _tmpLastError = null;
            } else {
              _tmpLastError = _cursor.getString(_cursorIndexOfLastError);
            }
            final Long _tmpSequenceNumber;
            if (_cursor.isNull(_cursorIndexOfSequenceNumber)) {
              _tmpSequenceNumber = null;
            } else {
              _tmpSequenceNumber = _cursor.getLong(_cursorIndexOfSequenceNumber);
            }
            final String _tmpUserId;
            if (_cursor.isNull(_cursorIndexOfUserId)) {
              _tmpUserId = null;
            } else {
              _tmpUserId = _cursor.getString(_cursorIndexOfUserId);
            }
            final String _tmpSessionId;
            if (_cursor.isNull(_cursorIndexOfSessionId)) {
              _tmpSessionId = null;
            } else {
              _tmpSessionId = _cursor.getString(_cursorIndexOfSessionId);
            }
            final String _tmpDeviceId;
            _tmpDeviceId = _cursor.getString(_cursorIndexOfDeviceId);
            final String _tmpSha256;
            if (_cursor.isNull(_cursorIndexOfSha256)) {
              _tmpSha256 = null;
            } else {
              _tmpSha256 = _cursor.getString(_cursorIndexOfSha256);
            }
            _item_1 = new BatchMetadata(_tmpPacketId,_tmpFilePath,_tmpStatus,_tmpCreatedTime,_tmpUploadTime,_tmpAckTime,_tmpFileSize,_tmpSampleCount,_tmpTransmissionMode,_tmpNtpOffset,_tmpBaseWallMs,_tmpDeviceUptimeNs,_tmpRetryCount,_tmpLastError,_tmpSequenceNumber,_tmpUserId,_tmpSessionId,_tmpDeviceId,_tmpSha256);
            _result.add(_item_1);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getById(final String packetId,
      final Continuation<? super BatchMetadata> $completion) {
    final String _sql = "SELECT * FROM batch_metadata WHERE packetId = ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, packetId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<BatchMetadata>() {
      @Override
      @Nullable
      public BatchMetadata call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfPacketId = CursorUtil.getColumnIndexOrThrow(_cursor, "packetId");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfCreatedTime = CursorUtil.getColumnIndexOrThrow(_cursor, "createdTime");
          final int _cursorIndexOfUploadTime = CursorUtil.getColumnIndexOrThrow(_cursor, "uploadTime");
          final int _cursorIndexOfAckTime = CursorUtil.getColumnIndexOrThrow(_cursor, "ackTime");
          final int _cursorIndexOfFileSize = CursorUtil.getColumnIndexOrThrow(_cursor, "fileSize");
          final int _cursorIndexOfSampleCount = CursorUtil.getColumnIndexOrThrow(_cursor, "sampleCount");
          final int _cursorIndexOfTransmissionMode = CursorUtil.getColumnIndexOrThrow(_cursor, "transmissionMode");
          final int _cursorIndexOfNtpOffset = CursorUtil.getColumnIndexOrThrow(_cursor, "ntpOffset");
          final int _cursorIndexOfBaseWallMs = CursorUtil.getColumnIndexOrThrow(_cursor, "baseWallMs");
          final int _cursorIndexOfDeviceUptimeNs = CursorUtil.getColumnIndexOrThrow(_cursor, "deviceUptimeNs");
          final int _cursorIndexOfRetryCount = CursorUtil.getColumnIndexOrThrow(_cursor, "retryCount");
          final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "lastError");
          final int _cursorIndexOfSequenceNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "sequenceNumber");
          final int _cursorIndexOfUserId = CursorUtil.getColumnIndexOrThrow(_cursor, "userId");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final int _cursorIndexOfDeviceId = CursorUtil.getColumnIndexOrThrow(_cursor, "deviceId");
          final int _cursorIndexOfSha256 = CursorUtil.getColumnIndexOrThrow(_cursor, "sha256");
          final BatchMetadata _result;
          if (_cursor.moveToFirst()) {
            final String _tmpPacketId;
            _tmpPacketId = _cursor.getString(_cursorIndexOfPacketId);
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final BatchStatus _tmpStatus;
            final String _tmp;
            _tmp = _cursor.getString(_cursorIndexOfStatus);
            _tmpStatus = __converters.toBatchStatus(_tmp);
            final long _tmpCreatedTime;
            _tmpCreatedTime = _cursor.getLong(_cursorIndexOfCreatedTime);
            final Long _tmpUploadTime;
            if (_cursor.isNull(_cursorIndexOfUploadTime)) {
              _tmpUploadTime = null;
            } else {
              _tmpUploadTime = _cursor.getLong(_cursorIndexOfUploadTime);
            }
            final Long _tmpAckTime;
            if (_cursor.isNull(_cursorIndexOfAckTime)) {
              _tmpAckTime = null;
            } else {
              _tmpAckTime = _cursor.getLong(_cursorIndexOfAckTime);
            }
            final long _tmpFileSize;
            _tmpFileSize = _cursor.getLong(_cursorIndexOfFileSize);
            final int _tmpSampleCount;
            _tmpSampleCount = _cursor.getInt(_cursorIndexOfSampleCount);
            final String _tmpTransmissionMode;
            _tmpTransmissionMode = _cursor.getString(_cursorIndexOfTransmissionMode);
            final Long _tmpNtpOffset;
            if (_cursor.isNull(_cursorIndexOfNtpOffset)) {
              _tmpNtpOffset = null;
            } else {
              _tmpNtpOffset = _cursor.getLong(_cursorIndexOfNtpOffset);
            }
            final long _tmpBaseWallMs;
            _tmpBaseWallMs = _cursor.getLong(_cursorIndexOfBaseWallMs);
            final long _tmpDeviceUptimeNs;
            _tmpDeviceUptimeNs = _cursor.getLong(_cursorIndexOfDeviceUptimeNs);
            final int _tmpRetryCount;
            _tmpRetryCount = _cursor.getInt(_cursorIndexOfRetryCount);
            final String _tmpLastError;
            if (_cursor.isNull(_cursorIndexOfLastError)) {
              _tmpLastError = null;
            } else {
              _tmpLastError = _cursor.getString(_cursorIndexOfLastError);
            }
            final Long _tmpSequenceNumber;
            if (_cursor.isNull(_cursorIndexOfSequenceNumber)) {
              _tmpSequenceNumber = null;
            } else {
              _tmpSequenceNumber = _cursor.getLong(_cursorIndexOfSequenceNumber);
            }
            final String _tmpUserId;
            if (_cursor.isNull(_cursorIndexOfUserId)) {
              _tmpUserId = null;
            } else {
              _tmpUserId = _cursor.getString(_cursorIndexOfUserId);
            }
            final String _tmpSessionId;
            if (_cursor.isNull(_cursorIndexOfSessionId)) {
              _tmpSessionId = null;
            } else {
              _tmpSessionId = _cursor.getString(_cursorIndexOfSessionId);
            }
            final String _tmpDeviceId;
            _tmpDeviceId = _cursor.getString(_cursorIndexOfDeviceId);
            final String _tmpSha256;
            if (_cursor.isNull(_cursorIndexOfSha256)) {
              _tmpSha256 = null;
            } else {
              _tmpSha256 = _cursor.getString(_cursorIndexOfSha256);
            }
            _result = new BatchMetadata(_tmpPacketId,_tmpFilePath,_tmpStatus,_tmpCreatedTime,_tmpUploadTime,_tmpAckTime,_tmpFileSize,_tmpSampleCount,_tmpTransmissionMode,_tmpNtpOffset,_tmpBaseWallMs,_tmpDeviceUptimeNs,_tmpRetryCount,_tmpLastError,_tmpSequenceNumber,_tmpUserId,_tmpSessionId,_tmpDeviceId,_tmpSha256);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<BatchMetadata>> getAllBatches() {
    final String _sql = "SELECT * FROM batch_metadata ORDER BY createdTime DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"batch_metadata"}, new Callable<List<BatchMetadata>>() {
      @Override
      @NonNull
      public List<BatchMetadata> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfPacketId = CursorUtil.getColumnIndexOrThrow(_cursor, "packetId");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfCreatedTime = CursorUtil.getColumnIndexOrThrow(_cursor, "createdTime");
          final int _cursorIndexOfUploadTime = CursorUtil.getColumnIndexOrThrow(_cursor, "uploadTime");
          final int _cursorIndexOfAckTime = CursorUtil.getColumnIndexOrThrow(_cursor, "ackTime");
          final int _cursorIndexOfFileSize = CursorUtil.getColumnIndexOrThrow(_cursor, "fileSize");
          final int _cursorIndexOfSampleCount = CursorUtil.getColumnIndexOrThrow(_cursor, "sampleCount");
          final int _cursorIndexOfTransmissionMode = CursorUtil.getColumnIndexOrThrow(_cursor, "transmissionMode");
          final int _cursorIndexOfNtpOffset = CursorUtil.getColumnIndexOrThrow(_cursor, "ntpOffset");
          final int _cursorIndexOfBaseWallMs = CursorUtil.getColumnIndexOrThrow(_cursor, "baseWallMs");
          final int _cursorIndexOfDeviceUptimeNs = CursorUtil.getColumnIndexOrThrow(_cursor, "deviceUptimeNs");
          final int _cursorIndexOfRetryCount = CursorUtil.getColumnIndexOrThrow(_cursor, "retryCount");
          final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "lastError");
          final int _cursorIndexOfSequenceNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "sequenceNumber");
          final int _cursorIndexOfUserId = CursorUtil.getColumnIndexOrThrow(_cursor, "userId");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final int _cursorIndexOfDeviceId = CursorUtil.getColumnIndexOrThrow(_cursor, "deviceId");
          final int _cursorIndexOfSha256 = CursorUtil.getColumnIndexOrThrow(_cursor, "sha256");
          final List<BatchMetadata> _result = new ArrayList<BatchMetadata>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final BatchMetadata _item;
            final String _tmpPacketId;
            _tmpPacketId = _cursor.getString(_cursorIndexOfPacketId);
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final BatchStatus _tmpStatus;
            final String _tmp;
            _tmp = _cursor.getString(_cursorIndexOfStatus);
            _tmpStatus = __converters.toBatchStatus(_tmp);
            final long _tmpCreatedTime;
            _tmpCreatedTime = _cursor.getLong(_cursorIndexOfCreatedTime);
            final Long _tmpUploadTime;
            if (_cursor.isNull(_cursorIndexOfUploadTime)) {
              _tmpUploadTime = null;
            } else {
              _tmpUploadTime = _cursor.getLong(_cursorIndexOfUploadTime);
            }
            final Long _tmpAckTime;
            if (_cursor.isNull(_cursorIndexOfAckTime)) {
              _tmpAckTime = null;
            } else {
              _tmpAckTime = _cursor.getLong(_cursorIndexOfAckTime);
            }
            final long _tmpFileSize;
            _tmpFileSize = _cursor.getLong(_cursorIndexOfFileSize);
            final int _tmpSampleCount;
            _tmpSampleCount = _cursor.getInt(_cursorIndexOfSampleCount);
            final String _tmpTransmissionMode;
            _tmpTransmissionMode = _cursor.getString(_cursorIndexOfTransmissionMode);
            final Long _tmpNtpOffset;
            if (_cursor.isNull(_cursorIndexOfNtpOffset)) {
              _tmpNtpOffset = null;
            } else {
              _tmpNtpOffset = _cursor.getLong(_cursorIndexOfNtpOffset);
            }
            final long _tmpBaseWallMs;
            _tmpBaseWallMs = _cursor.getLong(_cursorIndexOfBaseWallMs);
            final long _tmpDeviceUptimeNs;
            _tmpDeviceUptimeNs = _cursor.getLong(_cursorIndexOfDeviceUptimeNs);
            final int _tmpRetryCount;
            _tmpRetryCount = _cursor.getInt(_cursorIndexOfRetryCount);
            final String _tmpLastError;
            if (_cursor.isNull(_cursorIndexOfLastError)) {
              _tmpLastError = null;
            } else {
              _tmpLastError = _cursor.getString(_cursorIndexOfLastError);
            }
            final Long _tmpSequenceNumber;
            if (_cursor.isNull(_cursorIndexOfSequenceNumber)) {
              _tmpSequenceNumber = null;
            } else {
              _tmpSequenceNumber = _cursor.getLong(_cursorIndexOfSequenceNumber);
            }
            final String _tmpUserId;
            if (_cursor.isNull(_cursorIndexOfUserId)) {
              _tmpUserId = null;
            } else {
              _tmpUserId = _cursor.getString(_cursorIndexOfUserId);
            }
            final String _tmpSessionId;
            if (_cursor.isNull(_cursorIndexOfSessionId)) {
              _tmpSessionId = null;
            } else {
              _tmpSessionId = _cursor.getString(_cursorIndexOfSessionId);
            }
            final String _tmpDeviceId;
            _tmpDeviceId = _cursor.getString(_cursorIndexOfDeviceId);
            final String _tmpSha256;
            if (_cursor.isNull(_cursorIndexOfSha256)) {
              _tmpSha256 = null;
            } else {
              _tmpSha256 = _cursor.getString(_cursorIndexOfSha256);
            }
            _item = new BatchMetadata(_tmpPacketId,_tmpFilePath,_tmpStatus,_tmpCreatedTime,_tmpUploadTime,_tmpAckTime,_tmpFileSize,_tmpSampleCount,_tmpTransmissionMode,_tmpNtpOffset,_tmpBaseWallMs,_tmpDeviceUptimeNs,_tmpRetryCount,_tmpLastError,_tmpSequenceNumber,_tmpUserId,_tmpSessionId,_tmpDeviceId,_tmpSha256);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getTotalCount(final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM batch_metadata";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getStatusCounts(final Continuation<? super List<StatusCount>> $completion) {
    final String _sql = "SELECT status, COUNT(*) as count FROM batch_metadata GROUP BY status";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<StatusCount>>() {
      @Override
      @NonNull
      public List<StatusCount> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfStatus = 0;
          final int _cursorIndexOfCount = 1;
          final List<StatusCount> _result = new ArrayList<StatusCount>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final StatusCount _item;
            final BatchStatus _tmpStatus;
            final String _tmp;
            _tmp = _cursor.getString(_cursorIndexOfStatus);
            _tmpStatus = __converters.toBatchStatus(_tmp);
            final int _tmpCount;
            _tmpCount = _cursor.getInt(_cursorIndexOfCount);
            _item = new StatusCount(_tmpStatus,_tmpCount);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getTotalFileSize(final BatchStatus excludeStatus,
      final Continuation<? super Long> $completion) {
    final String _sql = "SELECT SUM(fileSize) FROM batch_metadata WHERE status != ?";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    final String _tmp = __converters.fromBatchStatus(excludeStatus);
    _statement.bindString(_argIndex, _tmp);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Long>() {
      @Override
      @Nullable
      public Long call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Long _result;
          if (_cursor.moveToFirst()) {
            final Long _tmp_1;
            if (_cursor.isNull(0)) {
              _tmp_1 = null;
            } else {
              _tmp_1 = _cursor.getLong(0);
            }
            _result = _tmp_1;
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getOldestBatch(final Continuation<? super BatchMetadata> $completion) {
    final String _sql = "SELECT * FROM batch_metadata ORDER BY createdTime ASC LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<BatchMetadata>() {
      @Override
      @Nullable
      public BatchMetadata call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfPacketId = CursorUtil.getColumnIndexOrThrow(_cursor, "packetId");
          final int _cursorIndexOfFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "filePath");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfCreatedTime = CursorUtil.getColumnIndexOrThrow(_cursor, "createdTime");
          final int _cursorIndexOfUploadTime = CursorUtil.getColumnIndexOrThrow(_cursor, "uploadTime");
          final int _cursorIndexOfAckTime = CursorUtil.getColumnIndexOrThrow(_cursor, "ackTime");
          final int _cursorIndexOfFileSize = CursorUtil.getColumnIndexOrThrow(_cursor, "fileSize");
          final int _cursorIndexOfSampleCount = CursorUtil.getColumnIndexOrThrow(_cursor, "sampleCount");
          final int _cursorIndexOfTransmissionMode = CursorUtil.getColumnIndexOrThrow(_cursor, "transmissionMode");
          final int _cursorIndexOfNtpOffset = CursorUtil.getColumnIndexOrThrow(_cursor, "ntpOffset");
          final int _cursorIndexOfBaseWallMs = CursorUtil.getColumnIndexOrThrow(_cursor, "baseWallMs");
          final int _cursorIndexOfDeviceUptimeNs = CursorUtil.getColumnIndexOrThrow(_cursor, "deviceUptimeNs");
          final int _cursorIndexOfRetryCount = CursorUtil.getColumnIndexOrThrow(_cursor, "retryCount");
          final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "lastError");
          final int _cursorIndexOfSequenceNumber = CursorUtil.getColumnIndexOrThrow(_cursor, "sequenceNumber");
          final int _cursorIndexOfUserId = CursorUtil.getColumnIndexOrThrow(_cursor, "userId");
          final int _cursorIndexOfSessionId = CursorUtil.getColumnIndexOrThrow(_cursor, "sessionId");
          final int _cursorIndexOfDeviceId = CursorUtil.getColumnIndexOrThrow(_cursor, "deviceId");
          final int _cursorIndexOfSha256 = CursorUtil.getColumnIndexOrThrow(_cursor, "sha256");
          final BatchMetadata _result;
          if (_cursor.moveToFirst()) {
            final String _tmpPacketId;
            _tmpPacketId = _cursor.getString(_cursorIndexOfPacketId);
            final String _tmpFilePath;
            _tmpFilePath = _cursor.getString(_cursorIndexOfFilePath);
            final BatchStatus _tmpStatus;
            final String _tmp;
            _tmp = _cursor.getString(_cursorIndexOfStatus);
            _tmpStatus = __converters.toBatchStatus(_tmp);
            final long _tmpCreatedTime;
            _tmpCreatedTime = _cursor.getLong(_cursorIndexOfCreatedTime);
            final Long _tmpUploadTime;
            if (_cursor.isNull(_cursorIndexOfUploadTime)) {
              _tmpUploadTime = null;
            } else {
              _tmpUploadTime = _cursor.getLong(_cursorIndexOfUploadTime);
            }
            final Long _tmpAckTime;
            if (_cursor.isNull(_cursorIndexOfAckTime)) {
              _tmpAckTime = null;
            } else {
              _tmpAckTime = _cursor.getLong(_cursorIndexOfAckTime);
            }
            final long _tmpFileSize;
            _tmpFileSize = _cursor.getLong(_cursorIndexOfFileSize);
            final int _tmpSampleCount;
            _tmpSampleCount = _cursor.getInt(_cursorIndexOfSampleCount);
            final String _tmpTransmissionMode;
            _tmpTransmissionMode = _cursor.getString(_cursorIndexOfTransmissionMode);
            final Long _tmpNtpOffset;
            if (_cursor.isNull(_cursorIndexOfNtpOffset)) {
              _tmpNtpOffset = null;
            } else {
              _tmpNtpOffset = _cursor.getLong(_cursorIndexOfNtpOffset);
            }
            final long _tmpBaseWallMs;
            _tmpBaseWallMs = _cursor.getLong(_cursorIndexOfBaseWallMs);
            final long _tmpDeviceUptimeNs;
            _tmpDeviceUptimeNs = _cursor.getLong(_cursorIndexOfDeviceUptimeNs);
            final int _tmpRetryCount;
            _tmpRetryCount = _cursor.getInt(_cursorIndexOfRetryCount);
            final String _tmpLastError;
            if (_cursor.isNull(_cursorIndexOfLastError)) {
              _tmpLastError = null;
            } else {
              _tmpLastError = _cursor.getString(_cursorIndexOfLastError);
            }
            final Long _tmpSequenceNumber;
            if (_cursor.isNull(_cursorIndexOfSequenceNumber)) {
              _tmpSequenceNumber = null;
            } else {
              _tmpSequenceNumber = _cursor.getLong(_cursorIndexOfSequenceNumber);
            }
            final String _tmpUserId;
            if (_cursor.isNull(_cursorIndexOfUserId)) {
              _tmpUserId = null;
            } else {
              _tmpUserId = _cursor.getString(_cursorIndexOfUserId);
            }
            final String _tmpSessionId;
            if (_cursor.isNull(_cursorIndexOfSessionId)) {
              _tmpSessionId = null;
            } else {
              _tmpSessionId = _cursor.getString(_cursorIndexOfSessionId);
            }
            final String _tmpDeviceId;
            _tmpDeviceId = _cursor.getString(_cursorIndexOfDeviceId);
            final String _tmpSha256;
            if (_cursor.isNull(_cursorIndexOfSha256)) {
              _tmpSha256 = null;
            } else {
              _tmpSha256 = _cursor.getString(_cursorIndexOfSha256);
            }
            _result = new BatchMetadata(_tmpPacketId,_tmpFilePath,_tmpStatus,_tmpCreatedTime,_tmpUploadTime,_tmpAckTime,_tmpFileSize,_tmpSampleCount,_tmpTransmissionMode,_tmpNtpOffset,_tmpBaseWallMs,_tmpDeviceUptimeNs,_tmpRetryCount,_tmpLastError,_tmpSequenceNumber,_tmpUserId,_tmpSessionId,_tmpDeviceId,_tmpSha256);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
