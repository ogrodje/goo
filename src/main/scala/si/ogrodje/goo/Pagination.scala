package si.ogrodje.goo

/** Guards query-string pagination params against unbounded / negative values.
  *
  * Without clamping, a client could request `?limit=100000000` and force the DB into a huge sort / result set (a cheap
  * DoS vector, amplified when every request hit a fresh connection). `limit` is bounded to `[1, MaxLimit]` and `offset`
  * to `[0, ..)`.
  */
object Pagination:
  val DefaultLimit: Int = 100
  val MaxLimit: Int     = 500

  def limit(maybe: Option[Int]): Int  = maybe.fold(DefaultLimit)(_.max(1).min(MaxLimit))
  def offset(maybe: Option[Int]): Int = maybe.fold(0)(_.max(0))
