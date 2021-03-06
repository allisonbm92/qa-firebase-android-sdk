// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.local;

import static com.google.firebase.firestore.util.Assert.fail;
import static com.google.firebase.firestore.util.Assert.hardAssert;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteProgram;
import android.database.sqlite.SQLiteStatement;
import android.support.annotation.VisibleForTesting;
import com.google.common.base.Function;
import com.google.firebase.firestore.auth.User;
import com.google.firebase.firestore.model.DatabaseId;
import com.google.firebase.firestore.util.Consumer;
import com.google.firebase.firestore.util.Logger;
import com.google.firebase.firestore.util.Supplier;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import javax.annotation.Nullable;

/**
 * A SQLite-backed instance of Persistence.
 *
 * <p>In addition to implementations of the methods in the Persistence interface, also contains
 * helper routines that make dealing with SQLite much more pleasant.
 */
public final class SQLitePersistence extends Persistence {

  /**
   * Creates the database name that is used to identify the database to be used with a Firestore
   * instance. Note that this needs to stay stable across releases. The database is uniquely
   * identified by a persistence key - usually the Firebase app name - and a DatabaseId (project and
   * database).
   *
   * <p>Format is "firestore.{persistence-key}.{project-id}.{database-id}".
   */
  @VisibleForTesting
  public static String databaseName(String persistenceKey, DatabaseId databaseId) {
    try {
      return "firestore."
          + URLEncoder.encode(persistenceKey, "utf-8")
          + "."
          + URLEncoder.encode(databaseId.getProjectId(), "utf-8")
          + "."
          + URLEncoder.encode(databaseId.getDatabaseId(), "utf-8");
    } catch (UnsupportedEncodingException e) {
      // UTF-8 is a mandatory encoding supported on every platform.
      throw new AssertionError(e);
    }
  }

  private final OpenHelper opener;
  private final LocalSerializer serializer;
  private SQLiteDatabase db;
  private boolean started;
  private final SQLiteQueryCache queryCache;
  private final SQLiteRemoteDocumentCache remoteDocumentCache;
  private final SQLiteLruReferenceDelegate referenceDelegate;

  public SQLitePersistence(
      Context context, String persistenceKey, DatabaseId databaseId, LocalSerializer serializer) {
    String databaseName = databaseName(persistenceKey, databaseId);
    this.opener = new OpenHelper(context, databaseName);
    this.serializer = serializer;
    this.queryCache = new SQLiteQueryCache(this, this.serializer);
    this.remoteDocumentCache = new SQLiteRemoteDocumentCache(this, this.serializer);
    this.referenceDelegate = new SQLiteLruReferenceDelegate(this);
  }

  @Override
  public void start() {
    hardAssert(!started, "SQLitePersistence double-started!");
    started = true;
    try {
      db = opener.getWritableDatabase();
    } catch (SQLiteDatabaseLockedException e) {
      // TODO: Use a better exception type
      throw new RuntimeException(
          "Failed to gain exclusive lock to the Firestore client's offline persistence. This"
              + " generally means you are using Firestore from multiple processes in your app."
              + " Keep in mind that multi-process Android apps execute the code in your"
              + " Application class in all processes, so you may need to avoid initializing"
              + " Firestore in your Application class. If you are intentionally using Firestore"
              + " from multiple processes, you can only enable offline persistence (i.e. call"
              + " setPersistenceEnabled(true)) in one of them.",
          e);
    }
    queryCache.start();
    referenceDelegate.start(queryCache.getHighestListenSequenceNumber());
  }

  @Override
  public void shutdown() {
    hardAssert(started, "SQLitePersistence shutdown without start!");
    started = false;
    db.close();
    db = null;
  }

  @Override
  public boolean isStarted() {
    return started;
  }

  @Override
  public ReferenceDelegate getReferenceDelegate() {
    return referenceDelegate;
  }

  @Override
  MutationQueue getMutationQueue(User user) {
    return new SQLiteMutationQueue(this, serializer, user);
  }

  @Override
  SQLiteQueryCache getQueryCache() {
    return queryCache;
  }

  @Override
  RemoteDocumentCache getRemoteDocumentCache() {
    return remoteDocumentCache;
  }

  @Override
  void runTransaction(String action, Runnable operation) {
    try {
      Logger.debug(TAG, "Starting transaction: %s", action);
      referenceDelegate.onTransactionStarted();
      db.beginTransaction();
      operation.run();

      // Note that an exception in operation.run() will prevent this code from running.
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
      referenceDelegate.onTransactionCommitted();
    }
  }

  @Override
  <T> T runTransaction(String action, Supplier<T> operation) {
    try {
      Logger.debug(TAG, "Starting transaction: %s", action);
      referenceDelegate.onTransactionStarted();
      db.beginTransaction();
      T value = operation.get();

      // Note that an exception in operation.run() will prevent this code from running.
      db.setTransactionSuccessful();
      return value;
    } finally {
      db.endTransaction();
      referenceDelegate.onTransactionCommitted();
    }
  }

  /**
   * A SQLiteOpenHelper that configures database connections just the way we like them, delegating
   * to SQLiteSchema to actually do the work of migration.
   *
   * <p>The order of events when opening a new connection is as follows:
   *
   * <ol>
   *   <li>New connection
   *   <li>onConfigure (API 16 and above)
   *   <li>onCreate / onUpgrade (optional; if version already matches these aren't called)
   *   <li>onOpen
   * </ol>
   *
   * <p>This OpenHelper attempts to obtain exclusive access to the database and attempts to do so as
   * early as possible. On Jelly Bean devices and above (some 98% of devices at time of writing)
   * this happens naturally during onConfigure. On pre-Jelly Bean devices all other methods ensure
   * that the configuration is applied before any action is taken.
   */
  private static class OpenHelper extends SQLiteOpenHelper {

    private boolean configured;

    OpenHelper(Context context, String databaseName) {
      super(context, databaseName, null, SQLiteSchema.VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
      // Note that this is only called automatically by the SQLiteOpenHelper base class on Jelly
      // Bean and above.
      configured = true;
      Cursor cursor = db.rawQuery("PRAGMA locking_mode = EXCLUSIVE", new String[0]);
      cursor.close();
    }

    /**
     * Ensures that onConfigure has been called. This should be called first from all methods other
     * than onConfigure to ensure that onConfigure has been called, even if running on a pre-Jelly
     * Bean device.
     */
    private void ensureConfigured(SQLiteDatabase db) {
      if (!configured) {
        onConfigure(db);
      }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
      ensureConfigured(db);
      new SQLiteSchema(db).runMigrations(0);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      ensureConfigured(db);
      new SQLiteSchema(db).runMigrations(oldVersion);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      ensureConfigured(db);

      // For now, we can safely do nothing.
      //
      // The only case that's possible at this point would be to downgrade from version 1 (present
      // in our first released version) to 0 (uninstalled). Nobody would want us to just wipe the
      // data so instead we just keep it around in the hope that they'll upgrade again :-).
      //
      // Note that if you uninstall a Firestore-based app, the database goes away completely. The
      // downgrade-then-upgrade case can only happen in very limited circumstances.
      //
      // We'll have to revisit this once we ship a migration past version 1, but this will
      // definitely be good enough for our initial launch.
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
      ensureConfigured(db);
    }
  }

  /**
   * Execute the given non-query SQL statement. Equivalent to <code>execute(prepare(sql), args)
   * </code>.
   */
  void execute(String sql, Object... args) {
    // Note that unlike db.query and friends, execSQL already takes Object[] bindArgs so there's no
    // need to go through the bind dance below.
    db.execSQL(sql, args);
  }

  /** Prepare the given non-query SQL statement. */
  SQLiteStatement prepare(String sql) {
    return db.compileStatement(sql);
  }

  /**
   * Execute the given prepared non-query statement using the supplied bind arguments.
   *
   * @return The number of rows affected.
   */
  int execute(SQLiteStatement statement, Object... args) {
    statement.clearBindings();
    bind(statement, args);
    return statement.executeUpdateDelete();
  }

  /**
   * Creates a new {@link Query} for the given SQL query. Supply binding arguments and execute by
   * chaining further methods off the query.
   */
  Query query(String sql) {
    return new Query(db, sql);
  }

  /**
   * A wrapper around SQLiteDatabase's various query methods that serves to file down the rough
   * edges of using the SQLiteDatabase API. The wrapper provides:
   *
   * <ul>
   *   <li>Strongly-typed bind parameters (see {@link #binding}).
   *   <li>Exception-proof resource management, reducing try/finally boilerplate for each query.
   *   <li>Lambda-friendly result processing, reducing cursor boilerplate.
   * </ul>
   *
   * <p>Taken together, Query transforms code like this:
   *
   * <pre class="code">
   *   List<MutationBatch> result = new ArrayList<>();
   *   Cursor cursor = db.rawQuery(
   *       "SELECT mutations FROM mutations WHERE uid = ? AND batch_id <= ?",
   *       new String[] { uid, String.valueOf(batchId) });
   *   try {
   *     while (cursor.moveToNext()) {
   *       result.add(decodeMutationBatch(cursor.getBlob(0)));
   *     }
   *   } finally {
   *     cursor.close();
   *   }
   *   return result;
   * </pre>
   *
   * <p>Into code like this:
   *
   * <pre class="code">
   *   List<MutationBatch> result = new ArrayList<>();
   *   db.query("SELECT mutations FROM mutations WHERE uid = ? AND batch_id <= ?")
   *       .binding(uid, batchId)
   *       .forEach(row -> result.add(decodeMutationBatch(row.getBlob(0))));
   *   return result;
   * </pre>
   */
  static class Query {
    private final SQLiteDatabase db;
    private final String sql;
    private CursorFactory cursorFactory;

    Query(SQLiteDatabase db, String sql) {
      this.db = db;
      this.sql = sql;
    }

    /**
     * Uses the given binding arguments as positional parameters for the query.
     *
     * <p>Note that unlike {@link SQLiteDatabase#rawQuery}, this method takes Object binding
     * objects. Values in the <tt>args</tt> array need to be of a type that's usable in any of the
     * SQLiteProgram bindFoo methods.
     *
     * @return this Query object, for chaining.
     */
    Query binding(Object... args) {
      // This is gross, but the best way to preserve both the readability of the caller (since
      // values don't have be arbitrarily converted to Strings) and allows BLOBs to be used as
      // bind arguments.
      //
      // The trick here is that since db.query and db.rawQuery take String[] bind arguments, we
      // need some other way to bind. db.execSQL takes Object[] bind arguments but doesn't actually
      // allow querying because it doesn't return a Cursor. SQLiteQuery does allow typed bind
      // arguments, but isn't directly usable.
      //
      // However, you can get to the SQLiteQuery indirectly by supplying a CursorFactory to
      // db.rawQueryWithFactory. The factory's newCursor method will be called with a new
      // SQLiteQuery, and now we can bind with typed values.

      cursorFactory =
          (db1, masterQuery, editTable, query) -> {
            bind(query, args);
            return new SQLiteCursor(masterQuery, editTable, query);
          };
      return this;
    }

    /**
     * Runs the query, calling the consumer once for each row in the results.
     *
     * @param consumer A consumer that will receive the first row.
     */
    void forEach(Consumer<Cursor> consumer) {
      Cursor cursor = null;
      try {
        cursor = startQuery();
        while (cursor.moveToNext()) {
          consumer.accept(cursor);
        }
      } finally {
        if (cursor != null) {
          cursor.close();
        }
      }
    }

    /**
     * Runs the query, calling the consumer on the first row of the results if one exists.
     *
     * @param consumer A consumer that will receive the first row.
     * @return The number of rows processed (either zero or one).
     */
    int first(Consumer<Cursor> consumer) {
      Cursor cursor = null;
      try {
        cursor = startQuery();
        if (cursor.moveToFirst()) {
          consumer.accept(cursor);
          return 1;
        }
        return 0;
      } finally {
        if (cursor != null) {
          cursor.close();
        }
      }
    }

    /**
     * Runs the query and applies the function to the first row of the results if it exists.
     *
     * @param function A function to apply to the first row.
     * @param <T> The type of the return value of the function.
     * @return The result of the function application if there were any rows in the results or null
     *     otherwise.
     */
    @Nullable
    <T> T firstValue(Function<Cursor, T> function) {
      Cursor cursor = null;
      try {
        cursor = startQuery();
        if (cursor.moveToFirst()) {
          return function.apply(cursor);
        }
        return null;
      } finally {
        if (cursor != null) {
          cursor.close();
        }
      }
    }

    /** Runs the query and returns true if the result was nonempty. */
    boolean isEmpty() {
      Cursor cursor = null;
      try {
        cursor = startQuery();
        return !cursor.moveToFirst();
      } finally {
        if (cursor != null) {
          cursor.close();
        }
      }
    }

    /** Starts the query against the database, supplying binding arguments if they exist. */
    private Cursor startQuery() {
      if (cursorFactory != null) {
        return db.rawQueryWithFactory(cursorFactory, sql, null, null);
      } else {
        return db.rawQuery(sql, null);
      }
    }
  }

  /**
   * Binds the given arguments to the given SQLite statement or query.
   *
   * <p>This method helps work around the fact that all of the querying methods on SQLiteDatabase
   * take an array of strings for bind arguments. Most values can be straightforwardly converted to
   * strings except BLOB literals, which must be base64 encoded and is just too painful.
   *
   * <p>It's possible to bind using typed arguments (including BLOBs) using SQLiteProgram objects.
   * This method bridges the gap by examining the types of the bindArgs and calling to the
   * appropriate bind method on the program.
   */
  private static void bind(SQLiteProgram program, Object[] bindArgs) {
    for (int i = 0; i < bindArgs.length; i++) {
      Object arg = bindArgs[i];
      if (arg == null) {
        program.bindNull(i + 1);
      } else if (arg instanceof String) {
        program.bindString(i + 1, (String) arg);
      } else if (arg instanceof Integer) {
        program.bindLong(i + 1, (Integer) arg);
      } else if (arg instanceof Long) {
        program.bindLong(i + 1, (Long) arg);
      } else if (arg instanceof Double) {
        program.bindDouble(i + 1, (Double) arg);
      } else if (arg instanceof byte[]) {
        program.bindBlob(i + 1, (byte[]) arg);
      } else {
        throw fail("Unknown argument %s of type %s", arg, arg.getClass());
      }
    }
  }
}
