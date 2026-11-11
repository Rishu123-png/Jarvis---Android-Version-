package com.jarvis.assistant.automation

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import org.json.JSONArray

/**
 * The log of what fired and what it did — a table now, instead of a preference string.
 *
 * History is the only thing in this app that grows without a ceiling. It used to be a JSON array
 * inside SharedPreferences, which meant every finished run read the whole array, decoded it, built a
 * new one and wrote the entire string back — quadratic work and a full-file rewrite for one row. A
 * table appends, orders and trims in the database, and the fifty rows the board shows are the only
 * rows it reads.
 *
 * Everything else in [AutomationStore] stays where it is on purpose: eight routines are a document,
 * not a dataset, and moving them would be ceremony with a migration risk attached.
 */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val workflowName: String,
    val at: Long,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val stopped: Boolean,
    val triggerLabel: String,
)

@Dao
interface RunDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(run: RunEntity)

    @Query("SELECT * FROM runs ORDER BY at DESC LIMIT :limit")
    fun recent(limit: Int): List<RunEntity>

    /** Keeps the newest [keep] rows. One statement, so trimming cannot half-finish. */
    @Query("DELETE FROM runs WHERE id NOT IN (SELECT id FROM runs ORDER BY at DESC LIMIT :keep)")
    fun trim(keep: Int)

    @Query("DELETE FROM runs")
    fun clear()

    @Query("SELECT COUNT(*) FROM runs")
    fun count(): Int
}

@Database(entities = [RunEntity::class], version = 1, exportSchema = false)
abstract class JarvisDatabase : RoomDatabase() {

    abstract fun runs(): RunDao

    companion object {
        @Volatile
        private var instance: JarvisDatabase? = null

        /**
         * One database for the process.
         *
         * `allowMainThreadQueries` is deliberate and narrow: the automation board reads fifty indexed
         * rows once, when it is opened. The alternative — making every screen that wants a count
         * collect a Flow — buys nothing for a read that costs less than the frame it happens in, and
         * the writes all happen off the main thread.
         */
        fun of(context: Context): JarvisDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                JarvisDatabase::class.java,
                "jarvis.db",
            )
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}

/** The small, boring API the rest of the app uses. */
object RunHistory {

    /** How many runs are kept. Fifty is what the board can show without scrolling for a minute. */
    const val KEEP = 50

    private const val LEGACY_KEY = "history"

    fun record(context: Context, record: RunRecord) {
        try {
            val database = JarvisDatabase.of(context)
            database.runs().insert(record.toEntity())
            database.runs().trim(KEEP)
        } catch (t: Throwable) {
            // History is a courtesy. A run that happened must not be reported as a failure because
            // the log could not be written.
        }
    }

    fun recent(context: Context, limit: Int = KEEP): List<RunRecord> = try {
        val database = JarvisDatabase.of(context)
        importLegacy(context, database)
        database.runs().recent(limit).map { it.toRecord() }
    } catch (t: Throwable) {
        emptyList()
    }

    fun clear(context: Context) {
        try {
            JarvisDatabase.of(context).runs().clear()
            // The old preference is dead as soon as the table exists; leaving it would resurrect
            // deleted history on the next import.
            AutomationStore.forgetLegacyHistory(context)
        } catch (t: Throwable) {
            // Nothing was ever guaranteed here.
        }
    }

    /**
     * Moves a preference-era history into the table, once.
     *
     * The operator may have runs from the build before this one. Reading them is cheap and losing
     * them would be rude; the preference is deleted after the copy so it cannot come back.
     */
    private fun importLegacy(context: Context, database: JarvisDatabase) {
        if (database.runs().count() > 0) return
        val raw = AutomationStore.legacyHistory(context) ?: return
        val array = try {
            JSONArray(raw)
        } catch (t: Throwable) {
            AutomationStore.forgetLegacyHistory(context)
            return
        }
        for (index in 0 until array.length()) {
            val record = RunRecord.fromJson(array.optJSONObject(index) ?: continue) ?: continue
            database.runs().insert(record.toEntity())
        }
        AutomationStore.forgetLegacyHistory(context)
    }

    private fun RunRecord.toEntity() = RunEntity(
        id = id,
        workflowId = workflowId,
        workflowName = workflowName,
        at = at,
        succeeded = succeeded,
        failed = failed,
        skipped = skipped,
        stopped = stopped,
        triggerLabel = triggerLabel,
    )

    private fun RunEntity.toRecord() = RunRecord(
        id = id,
        workflowId = workflowId,
        workflowName = workflowName,
        at = at,
        succeeded = succeeded,
        failed = failed,
        skipped = skipped,
        stopped = stopped,
        triggerLabel = triggerLabel,
    )
}
