/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator
package storage

import org.junit.Assert._
import org.junit._

class DayTest {

  @Test
  def dayToString(): Unit = {
    assertEquals("20100101", Day(2010, 1, 1).toString)
    assertEquals("00011231", Day(1, 12, 31).toString)
  }

  @Test
  def dayFromString(): Unit = {
    assertEquals(Day(2010, 1, 1), Day("20100101"))
    assertEquals(Day(1, 12, 31), Day("00011231"))
  }

  @Test
  def invalidDayFromString(): Unit = {
    assertTrue("fail to parse too-short day string", Day.parse("2010123").isFailure)
    assertTrue("fail to parse too-long day string", Day.parse("201012315").isFailure)
    assertTrue("fail to parse day made up of letters", Day.parse("yyyymmdd").isFailure)
    assertTrue("fail to parse too large month", Day.parse("20101301").isFailure)
    assertTrue("fail to parse too large day", Day.parse("20101232").isFailure)
  }

  @Test
  def canConstructToday(): Unit = {
    val today = Day.today()
    assertTrue("today is not in a past year", today.year >= 2013)
  }
}
