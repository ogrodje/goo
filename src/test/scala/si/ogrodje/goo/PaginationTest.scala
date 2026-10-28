package si.ogrodje.goo

import zio.test.*

object PaginationTest extends ZIOSpecDefault:
  def spec = suite("PaginationTest")(
    test("limit defaults to DefaultLimit when absent") {
      assertTrue(Pagination.limit(None) == Pagination.DefaultLimit)
    },
    test("limit is capped at MaxLimit") {
      assertTrue(
        Pagination.limit(Some(100_000_000)) == Pagination.MaxLimit,
        Pagination.limit(Some(Pagination.MaxLimit + 1)) == Pagination.MaxLimit
      )
    },
    test("limit has a floor of 1 for zero/negative values") {
      assertTrue(
        Pagination.limit(Some(0)) == 1,
        Pagination.limit(Some(-5)) == 1
      )
    },
    test("limit passes through valid in-range values") {
      assertTrue(
        Pagination.limit(Some(1)) == 1,
        Pagination.limit(Some(250)) == 250,
        Pagination.limit(Some(Pagination.MaxLimit)) == Pagination.MaxLimit
      )
    },
    test("offset defaults to 0 when absent") {
      assertTrue(Pagination.offset(None) == 0)
    },
    test("offset clamps negatives to 0 and passes through non-negatives") {
      assertTrue(
        Pagination.offset(Some(-1)) == 0,
        Pagination.offset(Some(0)) == 0,
        Pagination.offset(Some(42)) == 42
      )
    }
  )
