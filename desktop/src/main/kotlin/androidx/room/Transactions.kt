package androidx.room

/**
 * The Android-only `withTransaction` from room-ktx, for the shared Repository code. On the JVM,
 * Room 2.7 offers transactions through the connection API; DAO calls made inside the block
 * run on the same connection and therefore inside the transaction.
 */
suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
