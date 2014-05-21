/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator

import org.junit.Assert._
import org.junit.Test
import sbt.IO
import java.io.File

class TestIO {

  @Test
  def testTestTmpDirectory(): Unit = {
    val dirWas = IO.withTestTempDirectory { dir =>
      assertTrue("test tmp dir is in target/", dir.getPath.contains("target"))
      dir
    }
    assertTrue("tmp dir was deleted", !dirWas.exists())
  }

  @Test
  def testTestTmpDirectoryWithException(): Unit = {
    var dirWas: Option[File] = None
    class MyException extends RuntimeException("failing")
    try {
      IO.withTestTempDirectory { dir =>
        assertTrue("test tmp dir is in target/", dir.getPath.contains("target"))
        dirWas = Some(dir)
        throw new MyException
      }
    } catch {
      case e: MyException =>
    }
    assertTrue("tmp dir found", dirWas.isDefined)
    assertTrue("tmp dir was deleted after exception", !dirWas.get.exists())
  }

  @Test
  def testCreateFileViaTmp(): Unit = {
    IO.withTestTempDirectory { tmpDir =>
      val toCreate = new File(tmpDir, "hello.txt")
      IO.createViaTemporary(toCreate) { tmpFile =>
        IO.write(tmpFile, "HELLO")
      }
      val content = IO.read(toCreate)
      assertEquals("HELLO", content)
    }
  }
}
