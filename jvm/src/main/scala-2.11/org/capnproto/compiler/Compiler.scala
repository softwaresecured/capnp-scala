package org.capnproto.compiler

import java.nio.channels.Channels

import org.capnproto.runtime.Serialize

object Compiler {
  def main(args: Array[String]): Unit = {
    val chan = Channels.newChannel(System.in)
    val messageReader = Serialize.read(chan)

    val generator = new Generator(messageReader)

    for (requestedFile <- generator.request.requestedFiles.get) {
      val id = requestedFile.id
      val lines = generator.generateNode(id, "rootName")
      println(s"package ${generator.packageName}")
      println("")
      println(lines.stringify())
    }
  }
}
