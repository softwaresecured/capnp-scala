package org.murtsi.capnproto.compiler

import org.murtsi.capnproto.runtime.implicits._
import org.murtsi.capnproto.runtime.{MessageBuilder, Text}
import org.scalatest._
import SimpleGeneric._

class SimpleGenericSpec extends FlatSpec with Matchers {
  "A GenericMap" should "be able to read & write Text entries" in {
    val builder = new MessageBuilder()
    val map = builder.getRoot[GenericMap[Text, Text]]

    var i = 1
    for (entry <- map.initEntries(2)) {
      entry.key = s"key $i".text
      entry.value = s"value $i".text
      i += 1
    }

    val reader = map.asReader
    val entries = reader.entries.get

    assert(entries(0).key.get.toString == "key 1")
    assert(entries(0).value.get.toString == "value 1")

    assert(entries(1).key.get.toString == "key 2")
    assert(entries(1).value.get.toString == "value 2")
  }

  it should "be able to read & write struct Entries" in {
    val builder = new MessageBuilder()
    val map = builder.getRoot[GenericMap[Text, User]]
    val values = Vector(("Jane", 36), ("John", 18))
    for (((name, age), entry) <- values.zip(map.initEntries(2).toSeq)) {
      entry.key = name.text

      val user = entry.initValue()
      user.name = name
      user.age = age.toByte
    }

    val reader = map.asReader
    val entries = reader.entries.get

    def checkEntry(i: Int, name: String, age: Int) = {
      val entry = entries(i)
      assert(entry.key.get.toString == name)
      val user = entry.value.get
      assert(user.name.get.toString == name)
      assert(user.age == age.toByte)
    }

    checkEntry(0, "Jane", 36)
    checkEntry(1, "John", 18)
  }

  it should "be able to read & write entries with nested generics" in {
    val builder = new MessageBuilder()
    val map = builder.getRoot[GenericMap[Text, GenericMap[Text, Text]]]

    var (i, j) = (0, 0)
    for (innerMap <- map.initEntries(2)) {
      innerMap.key = s"entry$i".text
      for (entry <- innerMap.initValue().initEntries(2)) {
        entry.key = s"innerEntry$j".text
        entry.value = s"value$j".text
        j += 1
      }
      i += 1
    }

    val reader = map.asReader
    val entries = reader.entries.get

    def checkEntry(idx: Int, key: String, innerIdx: Int, innerKey: String, value: String): Unit = {
      val e = entries(idx)
      assert(e.key.get.toString == key)
      val innerEntries = e.value.get.entries.get(innerIdx)
      assert(innerEntries.key.get.toString == innerKey)
      assert(innerEntries.value.get.toString == value)
    }

    checkEntry(0, "entry0", 0, "innerEntry0", "value0")
    checkEntry(0, "entry0", 1, "innerEntry1", "value1")
    checkEntry(1, "entry1", 0, "innerEntry2", "value2")
    checkEntry(1, "entry1", 1, "innerEntry3", "value3")
  }
}
