package klite.jdbc

import ch.tutteli.atrium.api.fluent.en_GB.toEqual
import ch.tutteli.atrium.api.fluent.en_GB.toHaveSize
import ch.tutteli.atrium.api.verbs.expect
import io.mockk.mockk
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds

class PooledDataSourceTest {
  val db = mockk<DataSource>(relaxed = true)
  val pooled = PooledDataSource(db, maxSize = 3, timeout = 100.milliseconds)

  @Test fun pool() {
    val conns = (1..3).map { pooled.connection }
    expect(pooled.used.keys).toHaveSize(3)
    expect(pooled.pool).toHaveSize(0)

    val extra = GlobalScope.async { pooled.connection }
    expect(extra.isActive).toEqual(true)
    expect(extra.isCompleted).toEqual(false)
    conns.forEach { it.close() }

    runBlocking {
      val conn = extra.await()
      expect(pooled.used.keys).toHaveSize(1)
      expect(pooled.pool).toHaveSize(2)
      conn.close()
      expect(pooled.used.keys).toHaveSize(0)
      expect(pooled.pool).toHaveSize(3)
    }

    pooled.close()
    expect(pooled.pool).toHaveSize(0)
    expect(pooled.used.keys).toHaveSize(0)
  }
}
