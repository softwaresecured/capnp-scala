package org.capnproto.compiler

import java.nio.file.Paths

import org.capnproto.runtime.{MessageReader, Text}

import scala.collection.mutable

sealed trait Leaf {
  import Leaf._

  override def toString: String = this match {
    case Reader => "Reader"
    case Builder => "Builder"
    case Owned => "Owned"
    case Client => "Client"
    case Pipeline => "Pipeline"
    case Module => ""
    case List => "List"
    case ListBuilder => "List.Builder"
    case ListReader => "List.Reader"
  }
}

object Leaf {
  object Reader extends Leaf
  object Builder extends Leaf
  object Owned extends Leaf
  object Client extends Leaf
  object Pipeline extends Leaf
  object Module extends Leaf
  object List extends Leaf
  object ListBuilder extends Leaf
  object ListReader extends Leaf
}

object Generator {
  private val PackageNameAnnotationId = 0xee44ce11f43e05d7L
}

class Generator(message: MessageReader) {
  val request = message.getRoot(CodeGeneratorRequest)
  private val nodeMap = mutable.HashMap[Long, Node.Reader]()
  private val scopeMap = mutable.HashMap[Long, Seq[String]]()
  var _packageName: String = _
  def packageName: String = _packageName

  for (node <- request.nodes.get) {
    nodeMap(node.id) = node
  }

  for (requestedFile <- request.requestedFiles.get) {
    val id = requestedFile.id
    _packageName = {
      val value = nodeMap(id).annotations.flatMap(_.find(_.id == Generator.PackageNameAnnotationId)).get.value.get
      value match {
        case Value.Text(txt) => txt.toString
      }
    }
    val imports = requestedFile.imports.get

    for (imp <- imports;
         importPath <- imp.name) {
      val path = Paths.get(importPath.toString).toString
      val rootName = moduleName(s"""${path.toString.replace("-", "_")}""") // TODO: how does this work
      populateScope(Seq(rootName), imp.id)
    }

    populateScope(Seq(packageName), id)
  }

  def structTypePreamble(nodeId: Long, data: Int, pointers: Int) = Seq(Indent(Branch(
    Line(s"val typeId: Long = ${nodeId}L"), // TODO: Add as a Struct abstract method
    Line(s"override val structSize: org.capnproto.runtime.StructSize = new org.capnproto.runtime.StructSize($data, $pointers)"),
    BlankLine,
    Line("override type Reader = ReaderImpl"),
    Line("override type Builder = BuilderImpl"),
    BlankLine,
    Line("override val Builder: (org.capnproto.runtime.SegmentBuilder, Int, Int, Int, Short) => Builder = new BuilderImpl(_, _, _, _, _)"),
    Line("override val Reader: (org.capnproto.runtime.SegmentReader, Int, Int, Int, Short, Int) => Reader = new ReaderImpl(_, _, _, _, _, _)"),
    BlankLine)
  ))

  def generateNode(nodeId: Long, nodeName: String, parentNodeId: Option[Long] = None, groupDiscriminant: Option[Short] = None): FormattedText = {
    val output = mutable.ArrayBuffer[FormattedText]()
    val nestedOutput = mutable.ArrayBuffer[FormattedText]()

    val nodeReader = nodeMap(nodeId)
    for (nestedNodes <- nodeReader.nestedNodes;
         nestedNode <- nestedNodes) {
      val id = nestedNode.id
      nestedOutput += generateNode(id, scopeMap(id).last)
    }
    import Node._
    nodeReader match {
      case File() => output += Branch(nestedOutput:_*)
      case Node.Const(const) =>
        val styledName = scopeMap(nodeId).last.capitalize
        val (typ: String, contents: String) = (const.`type`.get, const.value.get) match {
          case (Type.Void(), Value.Void()) => ("Unit", "()")
          case (Type.Int8(), Value.Int8(b)) => ("Byte", b.toString)
          case (Type.Int16(), Value.Int16(s)) => ("Short", s.toString)
          case (Type.Int32(), Value.Int32(i)) => ("Int", i.toString)
          case (Type.Int64(), Value.Int64(l)) => ("Long", l.toString)
          case (Type.Uint8(), Value.Uint8(b)) => ("Byte", b.toString)
          case (Type.Uint16(), Value.Uint16(s)) => ("Short", s.toString)
          case (Type.Uint32(), Value.Uint32(i)) => ("Int", i.toString)
          case (Type.Uint64(), Value.Uint64(l)) => ("Long", l.toString)
          case (Type.Float32(), Value.Float32(f)) => ("Float", f.toString)
          case (Type.Float64(), Value.Float64(d)) => ("Double", d.toString)
          case (t, v) => throw new UnsupportedOperationException(s"$t constants are not implemented")
        }
        output += Line(s"val $styledName: $typ = $contents")
      case Struct(struct) =>
        val params = parametersTexts(nodeReader)
        output += BlankLine

        val isGeneric = nodeReader.isGeneric
        if (isGeneric) {
          // TODO: Implement generic structs
          output += Line(s"object $nodeName extends org.capnproto.runtime.Struct { ")
        } else {
          output += Line(s"object $nodeName extends org.capnproto.runtime.Struct { ")
        }

        val unionFields = mutable.ArrayBuffer[Field.Reader]()
        val builderMembers, readerMembers = mutable.ArrayBuffer[FormattedText]()
        var extractors: Option[FormattedText] = None

        val dataSize = struct.dataWordCount
        val pointerSize = struct.pointerCount
        val discriminantCount = struct.discriminantCount
        val discriminantOffset = struct.discriminantOffset

        output ++= structTypePreamble(nodeId, dataSize, pointerSize)

        (struct.isGroup, groupDiscriminant) match {
          case (true, Some(discriminant)) =>
            val parent = scopeMap(nodeId).dropRight(1).mkString(".")
            val fieldName = {
              val n = nodeName.toLowerCase.head + nodeName.tail
              // TODO: remove this silly hack for getting a proper field name for the group
              if (n.endsWith("_") && keywords.contains(nodeName.substring(0, nodeName.length-1))) {
                n.substring(0, n.length - 1)
              } else {
                n
              }
            }
            output ++= Seq("Reader", "Builder")
                .map(leaf =>
                  Indent(Branch(
                    Line(s"def unapply(value: $parent.$leaf): Option[$leaf] = {"),
                    Indent(Line(s"if (value._whichIndex == $discriminant) Some(value.$fieldName) else None")),
                  Line("}")))
                )
          case _ =>
        }

        val fields = struct.fields.get
        for (field <- fields) {
          val name = field.name.get
          val styledName = name.toString

          val discriminantValue = field.discriminantValue
          val isUnionField = discriminantValue != Field.NoDiscriminant

          val getterAccessLevel = if (isUnionField) s"private[$nodeName] " else ""
          if (isUnionField) {
            unionFields += field
          }

          def isVoid(t: Type.Reader): Boolean = t match {
            case Type.Void() => true
            case _ => false
          }

          (isUnionField, field) match {
            case (true, Field.Slot(slot)) if isVoid(slot.`type`.get) =>
            case _ =>
              val (ty, get) = getterText(field, isReader = true)
              val (tyB, getB) = getterText(field, isReader = false)

              readerMembers += Branch(
                Line(s"${getterAccessLevel}def ${methodName(styledName)}: $ty = {"),
                Indent(get),
                Line("}")
              )
              builderMembers += Branch(
                Line(s"${getterAccessLevel}def ${methodName(styledName)}: $tyB = {"),
                Indent(getB),
                Line("}")
              )
          }

          builderMembers += generateSetter(discriminantOffset, styledName, field)

          field match {
            case Field.Group(group) =>
              val groupDiscriminant = if (discriminantCount > 0) Some(discriminantValue) else None
              val id = group.typeId
              val text = generateNode(id, scopeMap(id).last, groupDiscriminant = groupDiscriminant)
              nestedOutput += text
            case _ =>
          }
        }

        if (discriminantCount > 0) {
          val readerResult = generateUnionExtractors(discriminantOffset, unionFields, isReader = true, params)
          val whichIndexDef = Line(s"private[$nodeName] def _whichIndex: Short = _getShortField($discriminantOffset)")
          readerMembers += whichIndexDef
          builderMembers += whichIndexDef
          extractors = Some(Indent(Branch(readerResult.extractors:_*)))
        }

        output += Indent(Branch(
          Line("class ReaderImpl(_segment: org.capnproto.runtime.SegmentReader, _dataOffset: Int, _pointers: Int, _dataSize: Int, _pointerCount: Short, _nestingLimit: Int) extends super.ReaderBase(_segment, _dataOffset, _pointers, _dataSize, _pointerCount, _nestingLimit) {"),
          Indent(Branch(readerMembers:_*)),
          Line("}")
        ))
        output += Indent(Branch(
          Line("class BuilderImpl(_segment: org.capnproto.runtime.SegmentBuilder, _dataOffset: Int, _pointers: Int, _dataSize: Int, _pointerCount: Short) extends super.BuilderBase(_segment, _dataOffset, _pointers, _dataSize, _pointerCount) {"),
          Indent(Branch(builderMembers:_*)),
          Line("}")
        ))

        extractors.foreach(output.+=)

        output += Branch(Indent(Branch(nestedOutput:_*)))
        output += Line("}")
      case Enum(enumReader) =>
        val names = scopeMap(nodeId)
        val enumClassName = moduleName(names.last.capitalize)
        output += BlankLine
        val members, enumerantValues = mutable.ArrayBuffer[FormattedText]()
        val enumerants = enumReader.enumerants.get

        val enumerantsSize = enumerants.size
        for (i <- 0 until enumerantsSize) {
          val enumerant = enumerants(i)

          val enumerantName = moduleName(enumerant.name.get.toString.capitalize)
          members += Line(s"object $enumerantName extends $enumClassName($i)")

          if (i < enumerantsSize - 1) {
            enumerantValues += Line(s"$enumClassName.$enumerantName,")
          } else {
            enumerantValues += Line(s"$enumClassName.$enumerantName")
          }
        }

        output += Branch(
          Line(s"sealed class $enumClassName(index: Short) extends org.capnproto.runtime.Enum(index)"),
          Line(s"object $enumClassName extends org.capnproto.runtime.EnumModule[$enumClassName] {"),
          Indent(Branch(
            Branch(
              Line(s"override val enumValues: Seq[$enumClassName] = Array("),
              Indent(Branch(enumerantValues:_*)),
              Line(")"),
              Line(s"val _wrappedValues: Seq[Option[$enumClassName]] = enumValues.map(Some(_))"),
              Line(s"def apply(value: Short): Option[$enumClassName] = if (value >= 0 && value < _wrappedValues.length) _wrappedValues(value) else None")
            ),
            Branch(members:_*)
          )),
          Line("}")
        )
    }

    Branch(output:_*)
  }

  def generatePipelineGetter(field: Field.Reader): FormattedText = {
    Line("PIPELINED_GETTER")
  }

  def groupType(group: Field.Group.Reader, isReader: Boolean): String = {
    val module = scopeMap(group.typeId).mkString(".")
    val suffix = if (isReader) "Reader" else "Builder"
    s"$module.$suffix"
  }

  def getterText(field: Field.Reader, isReader: Boolean): (String, FormattedText) = {
    import Field._

    field match {
      case Group(group) =>
        val t = groupType(group, isReader)
        if (isReader)
          (t, Line(s"$t(_segment, _dataOffset, _pointers, _dataSize, _pointerCount, _nestingLimit)"))
        else
          (t, Line(s"$t(_segment, _dataOffset, _pointers, _dataSize, _pointerCount)"))
      case Slot(regField) =>
        val offset = regField.offset
        val moduleString = if (isReader) "Reader" else "Builder"
        val module = if (isReader) "" else ""
        val member = moduleString
        val leaf = if (isReader) Leaf.Reader else Leaf.Builder

        val rawType = regField.`type`.get
        val typ = typeString(rawType, leaf)
        val default = regField.defaultValue.get

        val resultType = rawType match {
          case Type.Struct(_) | Type.Text() | Type.Data() | Type.Enum(_) =>
            s"Option[$typ]"
          case Type.List_(_) =>
            val listType = typeString(rawType, leaf)
            s"Option[$listType]"
          case Type.AnyPointer(_) if rawType.isParameter => typ
          case Type.AnyPointer(_) => s"Option[$typ]"
          case Type.Interface(_) => ???
          case _ if rawType.isPrimitive => typ
          case _ => typ
        }

        def primitiveCase[T](typ: String, offset: Long, default: T, zero: T): FormattedText = {
          if (default == zero) {
            Line(s"this._get${typ}Field($offset)")
          } else {
            Line(s"this._get${typ}Field($offset, $default)")
          }
        }
        val getterCode = (rawType, default) match {
          case (Type.Void(), Value.Void()) => Line("org.capnproto.runtime.Void")
          case (Type.Bool(), Value.Bool(bool)) =>
            if (bool) Line(s"_getBooleanField($offset, true)")
            else Line(s"_getBooleanField($offset)")
          case (Type.Int8(), Value.Int8(b)) => primitiveCase(typ, offset, b, 0)
          case (Type.Int16(), Value.Int16(s)) => primitiveCase(typ, offset, s, 0)
          case (Type.Int32(), Value.Int32(i)) => primitiveCase(typ, offset, i, 0)
          case (Type.Int64(), Value.Int64(l)) => primitiveCase(typ, offset, l, 0)
          case (Type.Uint8(), Value.Uint8(b)) => primitiveCase(typ, offset, b, 0)
          case (Type.Uint16(), Value.Uint16(s)) => primitiveCase(typ, offset, s, 0)
          case (Type.Uint32(), Value.Uint32(i)) => primitiveCase(typ, offset, i, 0)
          case (Type.Uint64(), Value.Uint64(l)) => primitiveCase(typ, offset, l, 0)
          case (Type.Float32(), Value.Float32(f)) => primitiveCase(typ, offset, f, 0)
          case (Type.Float64(), Value.Float64(d)) => primitiveCase(typ, offset, d, 0)
          case (Type.Text(), Value.Text(_)) => Line(s"_getPointerFieldOption(org.capnproto.runtime.Text, $offset)")
          case (Type.Data(), Value.Data(_)) => Line(s"_getPointerFieldOption(org.capnproto.runtime.Data, $offset)")
          case (Type.List_(_), Value.List_(_)) =>
            Line(s"_getPointerFieldOption(${typeString(rawType, Leaf.Module)}, $offset)")
          case (Type.Enum(_), Value.Enum(_)) =>
            val module = typeString(rawType, Leaf.Module)
            Line(s"$module(_getShortField($offset))")
          case (Type.Struct(_), Value.Struct(_)) =>
            val module = typeString(rawType, Leaf.Module)
            Line(s"_getPointerFieldOption($module, $offset)")
          case (Type.AnyPointer(_), Value.AnyPointer(_)) => Line(s"_getPointerFieldOption(org.capnproto.runtime.AnyPointer, $offset)")
          case (Type.Interface(_), Value.Interface()) => ???
          case _ => Line("") // throw new Error(s"Default value was of wrong type (expected ${typeString(rawType, Leaf.Module)}, got ${valueToString(default)})")
        }

        (resultType, getterCode)
    }
  }

  def valueToString(value: Value.Reader): String = {
    value match {
      case Value.Int8(b) => s"Int8($b)"
      case Value.Int16(s) => s"Int16($s)"
      case Value.Int32(i) => s"Int32($i)"
      case Value.Int64(l) => s"Int64($l)"
      case Value.Uint8(b) => s"Uint8($b)"
      case Value.Uint16(s) => s"Uint16($s)"
      case Value.Uint32(i) => s"UInt32($i)"
      case Value.Uint64(l) => s"UInt64($l)"
      case Value.Float32(f) => s"Float32($f)"
      case Value.Float64(d) => s"Float64($d)"
      case Value.Text(txt) => s"""Text("$txt")"""
      case Value.Data(data) => s"""Data(${data.toArray.mkString(", ")})"""
      case Value.List_(list) => s"""List(is null = ${list.isNull})"""
      case Value.Enum(enum) => s"""Enum($enum)"""
      case Value.Struct(struct) => s"""Struct(is null = ${struct.isNull})"""
      case Value.AnyPointer(ptr) => s"""AnyPointer(is null = ${ptr.isNull})"""
      case Value.Interface() => s"""Interface"""
      case v => s"UNKNOWN_TYPE($v)"
    }
  }

  def generateSetter(discriminantOffset: Int, styledName: String, field: Field.Reader): FormattedText = {
    val initterInterior, setterInterior = mutable.ArrayBuffer[FormattedText]()
    var setterParam = "value"
    val initterParams = mutable.ArrayBuffer[String]()
    var textSetterInterior: Option[FormattedText] = None

    val discriminantValue = field.discriminantValue
    if (discriminantValue != Field.NoDiscriminant) {
      val doStr = java.lang.Long.toUnsignedString(discriminantOffset.toLong)
      val dvStr = java.lang.Long.toUnsignedString(discriminantValue.toLong)
      setterInterior += Line(s"_setShortField($doStr, $dvStr)")
      initterInterior += Line(s"_setShortField($doStr, $dvStr)")
    }

    var setterGenericParam = ""
    var returnResult = false
    val result = mutable.ArrayBuffer[FormattedText]()

    val (maybeReaderType, maybeBuilderType) = field match {
      case Field.Group(group) =>
        val scope = scopeMap(group.typeId)
        val module = scope.mkString(".")
        initterInterior ++= Seq(
          zeroFieldsOfGroup(group.typeId),
          Line(s"$module.Builder(_segment, _dataOffset, _pointers, _dataSize, _pointerCount)"))
        (None, Some(s"$module.Builder"))
      case Field.Slot(regField) =>
        val offset = regField.offset
        val typ = regField.`type`.get
        typ match {
          case Type.Void() =>
            setterParam = "_value"
            (Some("Unit"), None)
          case Type.Bool() =>
            primitiveDefault(regField.defaultValue.get) match {
              case None => setterInterior += Line(s"_setBooleanField($offset, value)")
              case Some(s) => setterInterior += Line(s"_setBooleanField($offset, value, $s)")
            }
            (Some("Boolean"), None)
          case _ if typ.isPrimitive =>
            val tstr = typeString(typ, Leaf.Reader)
            primitiveDefault(regField.defaultValue.get) match {
              case None =>
                setterInterior += Line(s"_set${tstr}Field($offset, value)")
              case Some(s) =>
                setterInterior += Line(s"_set${tstr}Field($offset, value, $s)")
            }
            (Some(tstr), None)
          case Type.Text() =>
            setterInterior += Line(s"""_setPointerField(org.capnproto.runtime.Text)($offset, value)""")
            initterInterior += Line(s"_initPointerField(org.capnproto.runtime.Text, $offset, size)")
            textSetterInterior = Some(Line(s"_setPointerField(org.capnproto.runtime.Text)($offset, org.capnproto.runtime.Text.Reader(value))"))
            initterParams += "size: Int"
            (Some("org.capnproto.runtime.Text.Reader"), Some("org.capnproto.runtime.Text.Builder"))
          case Type.AnyPointer(_) =>
            initterInterior += Line(s"_initPointerField(org.capnproto.runtime.AnyPointer, $offset, size)")
            initterParams += "size: Int = 0"
            (Some("org.capnproto.runtime.AnyPointer.Reader"), Some("org.capnproto.runtime.AnyPointer.Builder"))
          case Type.Data() =>
            setterInterior += Line(s"""_setPointerField(org.capnproto.runtime.Data)($offset, value)""")
            initterInterior += Line(s"_initPointerField(org.capnproto.runtime.Data, $offset, size)")
            initterParams += "size: Int"
            (Some("org.capnproto.runtime.Data.Reader"), Some("org.capnproto.runtime.Data.Builder"))
          case Type.List_(_) =>
            val elementFactory = typeString(typ, Leaf.Module)
            setterInterior += Line(s"""_setPointerField($elementFactory)($offset, value)""")
            initterInterior += Line(s"_initPointerField($elementFactory, $offset, size)")
            initterParams += "size: Int"
            (Some(s"$elementFactory.Reader"), Some(s"$elementFactory.Builder"))
          case Type.Enum(_) =>
            val ty = typeString(typ, Leaf.Builder)
            setterInterior += Line(s"_setShortField(value.index, $offset)")
            (Some(ty), None)
          case Type.Struct(_) =>
            returnResult = true
            initterInterior += Line(s"_initPointerField(${typeString(typ, Leaf.Module)}, $offset, 0)")
            if (typ.isBranded) {
              setterInterior += Line(s"_setPointerField(${typeString(typ, Leaf.Module)})($offset, value)")
              (Some(typeString(typ, Leaf.Reader)), Some(typeString(typ, Leaf.Builder)))
            } else {
              setterInterior += Line(s"_setPointerField(${typeString(typ, Leaf.Module)})($offset, value)")
              val t = regField.`type`.get
              (Some(typeString(t, Leaf.Reader)), Some(typeString(t, Leaf.Builder)))
            }
          case Type.Interface(_) => ???
          case _ => throw new Error("Unrecognized type")
        }
    }

    maybeReaderType match {
      case Some(readerType) =>
        val retType = "Unit"
        result ++= Seq(
          Line(s"def ${styledName}_=($setterParam: $readerType): $retType = {"),
          Indent(Branch(setterInterior:_*)),
          Line(s"}")
        )

        textSetterInterior.foreach(interior => {
          result ++= Seq(
            Line(s"def ${styledName}_=($setterParam: String): $retType = {"),
            Indent(Branch(interior)),
            Line(s"}")
          )
        })
      case None =>
    }

    maybeBuilderType match {
      case Some(builderType) =>
        val args = initterParams.mkString(", ")
        result ++= Seq(
          Line(s"def init${styledName.capitalize}($args): $builderType = {"),
          Indent(Branch(initterInterior:_*)),
          Line(s"}")
        )
      case None =>
    }

    Branch(result:_*)
  }

  case class UnionResult(extractors: Seq[FormattedText])

  def generateUnionExtractors(discriminantOffset: Int, fields: Seq[Field.Reader], isReader: Boolean, params: TypeParameterTexts): UnionResult = {
    def hasExtractorObject(field: Field.Reader): Boolean = {
      field match {
        case Field.Slot(slot) => slot.`type` match {
          case Some(Type.Struct(structType)) =>
            nodeMap(structType.typeId) match {
              case Node.Struct(struct) => !struct.isGroup
              case _ => false
            }
          case Some(Type.Enum(_)) => false
          case _ => true
        }
        case _ => true
      }
    }

    val extractors = mutable.ArrayBuffer[FormattedText]()

    for (field <- fields.filter(hasExtractorObject)) {
      val dvalue = java.lang.Short.toUnsignedLong(field.discriminantValue)
      val rawFieldName = field.name.get.toString
      val fieldName = methodName(rawFieldName)
      val enumerantName = moduleName(rawFieldName.capitalize)

      val (ty, _) = getterText(field, isReader = true)
      val (tyB, _) = getterText(field, isReader = false)

      field match {
        case Field.Slot(slot) => slot.`type`.get match {
          case Type.Void() =>
            extractors += Branch(
              Line(s"object $enumerantName {"),
              Indent(Branch(
                Line(s"def unapply(value: Reader): Boolean = value._whichIndex == $dvalue"),
                Line(s"def unapply(value: Builder): Boolean = value._whichIndex == $dvalue")
              )),
              Line("}")
            )
          case _ =>
            val wrappedInOption = slot.`type`.get.isPrimitive || booleanMatch(field) { case Field.Group(_) => }
            val fieldGetter = if (wrappedInOption) s"Some(value.$fieldName)" else s"value.$fieldName"
            val unapplyType = if (wrappedInOption) s"Option[$ty]" else ty
            val unapplyTypeB = if (wrappedInOption) s"Option[$tyB]" else tyB
            extractors += Branch(
              Line(s"object $enumerantName {"),
              Indent(Branch(
                Branch(
                  Line(s"def unapply(value: Reader): $unapplyType = {"),
                  Indent(Branch(
                    Line(s"if (value._whichIndex == $dvalue) $fieldGetter else None")
                  )),
                  Line("}")
                ),
                Branch(
                  Line(s"def unapply(value: Builder): $unapplyTypeB = {"),
                  Indent(Branch(
                    Line(s"if (value._whichIndex == $dvalue) $fieldGetter else None")
                  )),
                  Line("}")
                )
              )),
              Line("}")
            )
          }
        case _ =>
      }
    }

    UnionResult(extractors)
  }

  def primitiveDefault(value: Value.Reader): Option[String] = {
    import Value._
    value match {
      case Bool(b) => if (b) Some("true") else None
      case Int8(b) => if (b == 0) None else Some(b.toString)
      case Int16(s) => if (s == 0) None else Some(s.toString)
      case Int32(i) => if (i == 0) None else Some(i.toString)
      case Int64(l) => if (l == 0) None else Some(l.toString)
      case Uint8(b) => if (b == 0) None else Some(b.toString)
      case Uint16(s) => if (s == 0) None else Some(s.toString)
      case Uint32(i) => if (i == 0) None else Some(i.toString)
      case Uint64(l) => if (l == 0) None else Some(l.toString)
      case Float32(f) => if (f == 0.0) None else Some(f.toString)
      case Float64(d) => if (d == 0.0) None else Some(d.toString)
      case _ => throw new Error(s"Expected a primitive value")
    }
  }

  def zeroFieldsOfGroup(nodeId: Long): FormattedText = {
    val node = nodeMap(nodeId)
    node match {
      case Node.Struct(st) =>
        val result = mutable.ArrayBuffer[FormattedText]()
        if (st.discriminantCount != 0) {
          result += Line(s"_setShortField(${st.discriminantOffset}, 0)")
        }
        val fields = st.fields.get
        for (field <- fields) {
          field match {
            case Field.Group(group) => result += zeroFieldsOfGroup(group.typeId)
            case Field.Slot(slot) =>
              slot.`type`.get match {
                case Type.Void() =>
                case Type.Bool() =>
                  val line = Line(s"_setBooleanField(${slot.offset}, false)")
                  if (!result.contains(line)) {
                    result += line
                  }
                case Type.Int8() | Type.Int16() | Type.Int32() | Type.Int64()
                    | Type.Uint8() | Type.Uint16() | Type.Uint32() | Type.Uint64()
                    | Type.Float32() | Type.Float64()  =>
                  val line = Line(s"_set${typeString(slot.`type`.get, Leaf.Module)}Field(${slot.offset}, 0)")
                  if (!result.contains(line)) result += line
                case Type.Enum(_) =>
                  val line = Line(s"_setShortField(${slot.offset}, 0)")
                  if (!result.contains(line)) result += line
                case Type.Struct(_) | Type.List_(_) | Type.Text() | Type.Data()
                     | Type.AnyPointer(_) | Type.Interface(_) =>
                  val line = Line(s"_clearPointerField(${slot.offset})")
                  if (!result.contains(line)) result += line
              }
          }
        }
        Branch(result:_*)
      case _ => throw new Error("Expected a struct")
    }
  }

  def populateScope(scopeNames: Seq[String], nodeId: Long): Unit = {

    scopeMap(nodeId) = scopeNames

    val nodeReader = nodeMap.get(nodeId) match {
      case Some(n) => n
      case _ => return
    }

    for (nestedNode <- nodeReader.nestedNodes.toSeq.flatten) {
      val nScopeNames = mutable.ArrayBuffer(scopeNames:_*)
      val nestedNodeId = nestedNode.id
      nodeMap.get(nestedNodeId) match {
        case None =>
        case Some(nNodeReader) =>
          nNodeReader match {
            case Node.Enum(_) =>
              nScopeNames += moduleName(nestedNode.name.get.toString)
              populateScope(nScopeNames, nestedNodeId)
            case _ =>
              populateScope(scopeNames :+ moduleName(nestedNode.name.get), nestedNodeId)
          }
      }
    }

    nodeReader match {
      case Node.Struct(struct) =>
        val fields = struct.fields.get
        for (field <- fields) {

          field match {
            case Field.Group(group) =>
              val name = moduleName(field.name.get.toString.capitalize)
              populateScope(scopeNames :+ name, group.typeId)
            case _ =>
          }
        }
      case _ =>
    }
  }

  def parametersTexts(node: Node.Reader, parentNodeId: Option[Long] = None): TypeParameterTexts = {
    if (node.isGeneric) {
      val params = getTypeParameters(node.id, parentNodeId)
      val typeParameters = params.map(_.toString).mkString(",")
      TypeParameterTexts(expandedList = params, params = typeParameters)
    } else {
      TypeParameterTexts(expandedList = Seq(), params = "")
    }
  }

  def getTypeParameters(nodeId: Long, _parentScopeId: Option[Long] = None): Seq[String] = {
    var parentScopeId = _parentScopeId
    var currentNodeId = nodeId
    val accumulator = mutable.ArrayBuffer[Seq[String]]()

    var run = true
    while (run) {
      nodeMap.get(currentNodeId) match {
        case None => run = false
        case Some(currentNode) =>
          val params = currentNode.parameters.get.map(_.name.toString)
          accumulator += params

          currentNodeId = currentNode.scopeId
          (currentNodeId, parentScopeId) match {
            case (0, Some(id)) => currentNodeId = id
            case _ => ()
          }

          parentScopeId = None
      }
    }

    accumulator.reverse.flatten
  }

  def moduleName(name: Text.Reader): String = {
    val nameStr = name.toString
    moduleName(nameStr)
  }

  def moduleName(name: String): String = {
    if (keywords.contains(name)) name+"_" else name
  }

  def methodName(name: String): String = if (keywords.contains(name)) s"`$name`" else name

  def doBranding(nodeId: Long, brand: Option[Brand.Reader], leaf: Leaf, module: String, _parentScopeId: Option[Long]): String = {
    var parentScopeId = _parentScopeId

    val brandScopes = (for (b <- brand;
                   scopes <- b.scopes)
        yield mutable.HashMap[Long, Brand.Scope.Reader](scopes.map(s => (s.scopeId, s)):_*))
      .getOrElse(mutable.HashMap())
    var currentNodeId = nodeId
    val accumulator = mutable.ArrayBuffer[Seq[String]]()

    var run = true
    while (run) {
      nodeMap.get(currentNodeId) match {
        case None => run = false
        case Some(currentNode) =>
          val params = currentNode.parameters.map(_.seq).getOrElse(Seq.empty)
          val arguments = mutable.ArrayBuffer[String]()
          brandScopes.get(currentNodeId) match {
            case None =>
              for (_ <- params) {
                arguments += "::capnp::any_pointer::Owned"
              }
            case Some(scope) =>
              scope match {
                case Brand.Scope.Inherit() =>
                  arguments ++= params.map(_.name.toString)
                case Brand.Scope.Bind(bindingsList) =>
                  assert(bindingsList.size == params.size)
                  arguments ++= bindingsList.map {
                    case v if booleanMatch(v){case Brand.Binding.Unbound() => } =>
                      "::capnp::any_pointer::Owned"
                    case Brand.Binding.Type(typ) =>
                      typeString(typ, Leaf.Owned)
                  }
              }
          }

          accumulator += arguments
          currentNodeId = currentNode.scopeId
          (currentNodeId, parentScopeId) match {
            case (0, Some(id)) => currentNodeId = id
            case _ =>
          }
          parentScopeId = None
      }
    }

    val args = accumulator.reverse.flatten

    val arguments = if (args.isEmpty) {
      ""
    } else {
      val argStr = args.mkString(", ")
      s"[$argStr]"
    }

    leaf match {
      case Leaf.Module => s"$module$arguments"
      case l => s"$module.$l$arguments"
    }
  }

  def typeString(typ: Type.Reader, module: Leaf): String = {
    val moduleSuffix = module match {
      case Leaf.Module => ""
      case leaf => s".$leaf"
    }

    import Type._
    typ match {
      case Void() => "org.capnproto.runtime.Void"
      case Bool() => "Boolean"
      case Int8() => "Byte"
      case Int16() => "Short"
      case Int32() => "Int"
      case Int64() => "Long"
      case Uint8() => "Byte"
      case Uint16() => "Short"
      case Uint32() => "Int"
      case Uint64() => "Long"
      case Float32() => "Float"
      case Float64() => "Double"
      case Type.Text() => s"org.capnproto.runtime.Text$moduleSuffix"
      case Data() => s"org.capnproto.runtime.Data$moduleSuffix"
      case Struct(st) =>
        val moduleName = scopeMap(st.typeId).mkString(".")
        doBranding(st.typeId, st.brand, module, moduleName, None)
      case Interface(interface) =>
        doBranding(interface.typeId, interface.brand, module, scopeMap(interface.typeId).mkString("."), None)
      case List_(ot1) =>
        val elType = ot1.elementType.get
        listTypeString(elType, module)
      case Enum(enum) =>
        scopeMap(enum.typeId).mkString(".")
      case AnyPointer(pointer)=>
        pointer match {
          case AnyPointer.Parameter(definition) =>
            val theStruct = nodeMap(definition.scopeId)
            val parameters = theStruct.parameters.get
            val parameter = parameters(definition.parameterIndex)
            val parameterName = parameter.name
            module match {
              case Leaf.Owned => parameterName.toString
              case Leaf.Reader => s"$parameterName.Reader"
              case Leaf.Builder => s"$parameterName.Builder"
              case _ => ???
            }
          case _ =>
            module match {
              case Leaf.Reader => "org.capnproto.runtime.AnyPointer.Reader"
              case Leaf.Builder => "org.capnproto.runtime.AnyPointer.Builder"
              case Leaf.Module => "org.capnproto.runtime.AnyPointer"
            }
        }
    }
  }

  def listTypeString(elementType: Type.Reader, module: Leaf): String = {
    val moduleSuffix = module match {
      case Leaf.Module => ""
      case m => s".$m"
    }
    import Type._
    elementType match {
      case Struct(_) | Enum(_) => typeString(elementType, Leaf.Module) + s".List$moduleSuffix"
      case Type.Text() => s"org.capnproto.runtime.Text.List$moduleSuffix"
      case AnyPointer(_) => s"org.capnproto.runtime.AnyPointer.List$moduleSuffix"
      case Data() => s"org.capnproto.runtime.Data.List$moduleSuffix"
      case _ if elementType.isPrimitive => s"org.capnproto.runtime.PrimitiveList.${typeString(elementType, Leaf.Module)}$moduleSuffix"
    }
  }
}
