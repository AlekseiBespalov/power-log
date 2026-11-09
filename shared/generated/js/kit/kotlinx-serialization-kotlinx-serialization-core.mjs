import {
  protoOf180f3jzyo7rfj as protoOf,
  initMetadataForInterface1egvbzx539z91 as initMetadataForInterface,
  initMetadataForClassbxx6q50dy2s7 as initMetadataForClass,
  VOID3gxj6tk5isa35 as VOID,
  isBlank1dvkhjjvox3p0 as isBlank,
  toString1pkumu07cwy4m as toString,
  IllegalArgumentException_init_$Create$1gox8sdqs91jy as IllegalArgumentException_init_$Create$,
  Unit_instance3kz1zqli6gr1r as Unit_instance,
  equals2au1ep9vhcato as equals,
  toList383f556t1dixk as toList,
  emptyList1g2z5xcrvp2zy as emptyList,
  ArrayList_init_$Create$2xrifdqz5xirg as ArrayList_init_$Create$,
  HashSet_init_$Create$b2fvq2b4c4v2 as HashSet_init_$Create$,
  KProperty1ca4yb4wlo496 as KProperty1,
  getPropertyCallableRef3hckxc0xueiaj as getPropertyCallableRef,
  constructCallableReference23y65rf941mch as constructCallableReference,
  toHashSet1qrcsl3g8ugc8 as toHashSet,
  copyToArray2j022khrow2yi as copyToArray,
  toBooleanArray2u3qw7fjwsmuh as toBooleanArray,
  withIndex3s8q7w1g0hyfn as withIndex,
  collectionSizeOrDefault36dulx8yinfqm as collectionSizeOrDefault,
  ArrayList_init_$Create$a978fvbbts3d as ArrayList_init_$Create$_0,
  to2cs3ny02qtbcb as to,
  toMap1vec9topfei08 as toMap,
  lazy2hsh8ze7j6ikd as lazy,
  contentEqualsaf55p28mnw74 as contentEquals,
  initMetadataForObject1cxne3s9w65el as initMetadataForObject,
  getKClassFromExpression348iqjl4fnx2f as getKClassFromExpression,
  ensureNotNull1e947j3ixpazm as ensureNotNull,
  getStringHashCode26igk1bx568vk as getStringHashCode,
  objectCreate1ve4bgxiu4x98 as objectCreate,
  isInterface3d6p8outrmvmk as isInterface,
  joinToString1cxrrlmo0chqs as joinToString,
  hashCodeq5arwsb9dgti as hashCode,
  getOrNull1d60i0672n7ns as getOrNull,
  HashSet_init_$Create$2izef1i1668r1 as HashSet_init_$Create$_0,
  HashMap_init_$Create$g6b5bm370ovq as HashMap_init_$Create$,
  booleanArray2jdug9b51huk7 as booleanArray,
  emptyMapr06gerzljqtm as emptyMap,
  LazyThreadSafetyMode_PUBLICATION_getInstance23f213579at67 as LazyThreadSafetyMode_PUBLICATION_getInstance,
  lazy1261dae0bgscp as lazy_0,
  contentHashCode2i020q5tbeh2s as contentHashCode,
  until1jbpn0z3f8lbg as until,
  get_indicesc04v40g017hw as get_indices,
  IndexOutOfBoundsException_init_$Create$k95f9vzk11af as IndexOutOfBoundsException_init_$Create$,
} from './kotlin-kotlin-stdlib.mjs';
//region block: imports
var imul = Math.imul;
//endregion
//region block: pre-declaration
initMetadataForInterface(SerialDescriptor, 'SerialDescriptor');
initMetadataForClass(elementNames$1);
initMetadataForClass(elementNames$$inlined$Iterable$1);
initMetadataForClass(elementDescriptors$1);
initMetadataForClass(elementDescriptors$$inlined$Iterable$1);
initMetadataForClass(ClassSerialDescriptorBuilder, 'ClassSerialDescriptorBuilder');
initMetadataForInterface(CachedNames, 'CachedNames');
initMetadataForClass(SerialDescriptorImpl, 'SerialDescriptorImpl', VOID, VOID, [SerialDescriptor, CachedNames]);
initMetadataForClass(SerialKind, 'SerialKind');
initMetadataForObject(ENUM, 'ENUM', VOID, SerialKind);
initMetadataForClass(StructureKind, 'StructureKind', VOID, SerialKind);
initMetadataForObject(CLASS, 'CLASS', VOID, StructureKind);
initMetadataForObject(OBJECT, 'OBJECT', VOID, StructureKind);
initMetadataForClass(EnumSerializer, 'EnumSerializer');
initMetadataForClass(PluginGeneratedSerialDescriptor, 'PluginGeneratedSerialDescriptor', VOID, VOID, [SerialDescriptor, CachedNames]);
initMetadataForClass(EnumDescriptor, 'EnumDescriptor', VOID, PluginGeneratedSerialDescriptor);
function typeParametersSerializers() {
  return get_EMPTY_SERIALIZER_ARRAY();
}
initMetadataForInterface(GeneratedSerializer, 'GeneratedSerializer');
initMetadataForClass(SerializableWith, 'SerializableWith', VOID, VOID, VOID, VOID, 0);
//endregion
function SerialDescriptor() {
}
function get_elementNames(_this__u8e3s4) {
  // Inline function 'kotlin.collections.Iterable' call
  return new elementNames$$inlined$Iterable$1(_this__u8e3s4);
}
function get_elementDescriptors(_this__u8e3s4) {
  // Inline function 'kotlin.collections.Iterable' call
  return new elementDescriptors$$inlined$Iterable$1(_this__u8e3s4);
}
function elementNames$1($this_elementNames) {
  this.$this_elementNames_1 = $this_elementNames;
  this.elementsLeft_1 = $this_elementNames.get_elementsCount_288r0x_k$();
}
protoOf(elementNames$1).hasNext_bitz1p_k$ = function () {
  return this.elementsLeft_1 > 0;
};
protoOf(elementNames$1).next_20eer_k$ = function () {
  var tmp = this.$this_elementNames_1.get_elementsCount_288r0x_k$();
  var _unary__edvuaz = this.elementsLeft_1;
  this.elementsLeft_1 = _unary__edvuaz - 1 | 0;
  return this.$this_elementNames_1.getElementName_u4sqmf_k$(tmp - _unary__edvuaz | 0);
};
function elementNames$$inlined$Iterable$1($this_elementNames) {
  this.$this_elementNames_1 = $this_elementNames;
}
protoOf(elementNames$$inlined$Iterable$1).iterator_jk1svi_k$ = function () {
  return new elementNames$1(this.$this_elementNames_1);
};
function elementDescriptors$1($this_elementDescriptors) {
  this.$this_elementDescriptors_1 = $this_elementDescriptors;
  this.elementsLeft_1 = $this_elementDescriptors.get_elementsCount_288r0x_k$();
}
protoOf(elementDescriptors$1).hasNext_bitz1p_k$ = function () {
  return this.elementsLeft_1 > 0;
};
protoOf(elementDescriptors$1).next_20eer_k$ = function () {
  var tmp = this.$this_elementDescriptors_1.get_elementsCount_288r0x_k$();
  var _unary__edvuaz = this.elementsLeft_1;
  this.elementsLeft_1 = _unary__edvuaz - 1 | 0;
  return this.$this_elementDescriptors_1.getElementDescriptor_ncda77_k$(tmp - _unary__edvuaz | 0);
};
function elementDescriptors$$inlined$Iterable$1($this_elementDescriptors) {
  this.$this_elementDescriptors_1 = $this_elementDescriptors;
}
protoOf(elementDescriptors$$inlined$Iterable$1).iterator_jk1svi_k$ = function () {
  return new elementDescriptors$1(this.$this_elementDescriptors_1);
};
function buildSerialDescriptor(serialName, kind, typeParameters, builder) {
  var tmp;
  if (builder === VOID) {
    tmp = buildSerialDescriptor$lambda;
  } else {
    tmp = builder;
  }
  builder = tmp;
  // Inline function 'kotlin.text.isNotBlank' call
  // Inline function 'kotlin.require' call
  if (!!isBlank(serialName)) {
    var message = 'Blank serial names are prohibited';
    throw IllegalArgumentException_init_$Create$(toString(message));
  }
  // Inline function 'kotlin.require' call
  if (!!equals(kind, CLASS_getInstance())) {
    var message_0 = "For StructureKind.CLASS please use 'buildClassSerialDescriptor' instead";
    throw IllegalArgumentException_init_$Create$(toString(message_0));
  }
  var sdBuilder = new ClassSerialDescriptorBuilder(serialName);
  builder(sdBuilder);
  return new SerialDescriptorImpl(serialName, kind, sdBuilder.elementNames_1.get_size_woubt6_k$(), toList(typeParameters), sdBuilder);
}
function ClassSerialDescriptorBuilder(serialName) {
  this.serialName_1 = serialName;
  this.isNullable_1 = false;
  this.annotations_1 = emptyList();
  this.elementNames_1 = ArrayList_init_$Create$();
  this.uniqueNames_1 = HashSet_init_$Create$();
  this.elementDescriptors_1 = ArrayList_init_$Create$();
  this.elementAnnotations_1 = ArrayList_init_$Create$();
  this.elementOptionality_1 = ArrayList_init_$Create$();
}
function _get__hashCode__tgwhef($this) {
  var tmp0 = $this._hashCode$delegate_1;
  var tmp = KProperty1;
  // Inline function 'kotlin.getValue' call
  getPropertyCallableRef('_hashCode', 1, tmp, SerialDescriptorImpl$_get__hashCode_$ref_2v7wzp(), null);
  return tmp0.get_value_j01efc_k$();
}
function SerialDescriptorImpl$_hashCode$delegate$lambda(this$0) {
  return function () {
    return hashCodeImpl(this$0, this$0.typeParametersDescriptors_1);
  };
}
function SerialDescriptorImpl$_get__hashCode_$ref_2v7wzp() {
  return constructCallableReference(function (p0) {
    return _get__hashCode__tgwhef(p0);
  }, 1, 0, 0);
}
function SerialDescriptorImpl(serialName, kind, elementsCount, typeParameters, builder) {
  this.serialName_1 = serialName;
  this.kind_1 = kind;
  this.elementsCount_1 = elementsCount;
  this.annotations_1 = builder.annotations_1;
  this.serialNames_1 = toHashSet(builder.elementNames_1);
  var tmp = this;
  // Inline function 'kotlin.collections.toTypedArray' call
  var this_0 = builder.elementNames_1;
  tmp.elementNames_1 = copyToArray(this_0);
  this.elementDescriptors_1 = compactArray(builder.elementDescriptors_1);
  var tmp_0 = this;
  // Inline function 'kotlin.collections.toTypedArray' call
  var this_1 = builder.elementAnnotations_1;
  tmp_0.elementAnnotations_1 = copyToArray(this_1);
  this.elementOptionality_1 = toBooleanArray(builder.elementOptionality_1);
  var tmp_1 = this;
  // Inline function 'kotlin.collections.map' call
  var this_2 = withIndex(this.elementNames_1);
  // Inline function 'kotlin.collections.mapTo' call
  var destination = ArrayList_init_$Create$_0(collectionSizeOrDefault(this_2, 10));
  var _iterator__ex2g4s = this_2.iterator_jk1svi_k$();
  while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var item = _iterator__ex2g4s.next_20eer_k$();
    var tmp$ret$4 = to(item.value_1, item.index_1);
    destination.add_utx5q5_k$(tmp$ret$4);
  }
  tmp_1.name2Index_1 = toMap(destination);
  this.typeParametersDescriptors_1 = compactArray(typeParameters);
  var tmp_2 = this;
  tmp_2._hashCode$delegate_1 = lazy(SerialDescriptorImpl$_hashCode$delegate$lambda(this));
}
protoOf(SerialDescriptorImpl).get_serialName_u2rqhk_k$ = function () {
  return this.serialName_1;
};
protoOf(SerialDescriptorImpl).get_kind_wop7ml_k$ = function () {
  return this.kind_1;
};
protoOf(SerialDescriptorImpl).get_elementsCount_288r0x_k$ = function () {
  return this.elementsCount_1;
};
protoOf(SerialDescriptorImpl).get_serialNames_8zf3cl_k$ = function () {
  return this.serialNames_1;
};
protoOf(SerialDescriptorImpl).getElementName_u4sqmf_k$ = function (index) {
  return getChecked(this.elementNames_1, index);
};
protoOf(SerialDescriptorImpl).getElementIndex_utfbym_k$ = function (name) {
  var tmp0_elvis_lhs = this.name2Index_1.get_wei43m_k$(name);
  return tmp0_elvis_lhs == null ? -3 : tmp0_elvis_lhs;
};
protoOf(SerialDescriptorImpl).getElementDescriptor_ncda77_k$ = function (index) {
  return getChecked(this.elementDescriptors_1, index);
};
protoOf(SerialDescriptorImpl).equals = function (other) {
  var tmp$ret$0;
  $l$block_5: {
    // Inline function 'kotlinx.serialization.internal.equalsImpl' call
    if (this === other) {
      tmp$ret$0 = true;
      break $l$block_5;
    }
    if (!(other instanceof SerialDescriptorImpl)) {
      tmp$ret$0 = false;
      break $l$block_5;
    }
    if (!(this.get_serialName_u2rqhk_k$() === other.get_serialName_u2rqhk_k$())) {
      tmp$ret$0 = false;
      break $l$block_5;
    }
    if (!contentEquals(this.typeParametersDescriptors_1, other.typeParametersDescriptors_1)) {
      tmp$ret$0 = false;
      break $l$block_5;
    }
    if (!(this.get_elementsCount_288r0x_k$() === other.get_elementsCount_288r0x_k$())) {
      tmp$ret$0 = false;
      break $l$block_5;
    }
    var inductionVariable = 0;
    var last = this.get_elementsCount_288r0x_k$();
    if (inductionVariable < last)
      do {
        var index = inductionVariable;
        inductionVariable = inductionVariable + 1 | 0;
        if (!(this.getElementDescriptor_ncda77_k$(index).get_serialName_u2rqhk_k$() === other.getElementDescriptor_ncda77_k$(index).get_serialName_u2rqhk_k$())) {
          tmp$ret$0 = false;
          break $l$block_5;
        }
        if (!equals(this.getElementDescriptor_ncda77_k$(index).get_kind_wop7ml_k$(), other.getElementDescriptor_ncda77_k$(index).get_kind_wop7ml_k$())) {
          tmp$ret$0 = false;
          break $l$block_5;
        }
      }
       while (inductionVariable < last);
    tmp$ret$0 = true;
  }
  return tmp$ret$0;
};
protoOf(SerialDescriptorImpl).hashCode = function () {
  return _get__hashCode__tgwhef(this);
};
protoOf(SerialDescriptorImpl).toString = function () {
  return toStringImpl(this);
};
function buildSerialDescriptor$lambda(_this__u8e3s4) {
  return Unit_instance;
}
function ENUM() {
  ENUM_instance = this;
  SerialKind.call(this);
}
var ENUM_instance;
function ENUM_getInstance() {
  if (ENUM_instance == null)
    new ENUM();
  return ENUM_instance;
}
function SerialKind() {
}
protoOf(SerialKind).toString = function () {
  return ensureNotNull(getKClassFromExpression(this).get_simpleName_r6f8py_k$());
};
protoOf(SerialKind).hashCode = function () {
  return getStringHashCode(this.toString());
};
function CLASS() {
  CLASS_instance = this;
  StructureKind.call(this);
}
var CLASS_instance;
function CLASS_getInstance() {
  if (CLASS_instance == null)
    new CLASS();
  return CLASS_instance;
}
function OBJECT() {
  OBJECT_instance = this;
  StructureKind.call(this);
}
var OBJECT_instance;
function OBJECT_getInstance() {
  if (OBJECT_instance == null)
    new OBJECT();
  return OBJECT_instance;
}
function StructureKind() {
  SerialKind.call(this);
}
function CachedNames() {
}
function createSimpleEnumSerializer(serialName, values) {
  return new EnumSerializer(serialName, values);
}
function EnumSerializer_init_$Init$(serialName, values, descriptor, $this) {
  EnumSerializer.call($this, serialName, values);
  $this.overriddenDescriptor_1 = descriptor;
  return $this;
}
function EnumSerializer_init_$Create$(serialName, values, descriptor) {
  return EnumSerializer_init_$Init$(serialName, values, descriptor, objectCreate(protoOf(EnumSerializer)));
}
function createUnmarkedDescriptor($this, serialName) {
  var d = new EnumDescriptor(serialName, $this.values_1.length);
  // Inline function 'kotlin.collections.forEach' call
  var indexedObject = $this.values_1;
  var inductionVariable = 0;
  var last = indexedObject.length;
  while (inductionVariable < last) {
    var element = indexedObject[inductionVariable];
    inductionVariable = inductionVariable + 1 | 0;
    d.addElement$default_mty55e_k$(element.name_1);
  }
  return d;
}
function EnumSerializer$descriptor$delegate$lambda(this$0, $serialName) {
  return function () {
    var tmp0_elvis_lhs = this$0.overriddenDescriptor_1;
    return tmp0_elvis_lhs == null ? createUnmarkedDescriptor(this$0, $serialName) : tmp0_elvis_lhs;
  };
}
function EnumSerializer$_get_descriptor_$ref_j67dlw() {
  return constructCallableReference(function (p0) {
    return p0.get_descriptor_wjt6a0_k$();
  }, 1, 0, 1);
}
function EnumSerializer(serialName, values) {
  this.values_1 = values;
  this.overriddenDescriptor_1 = null;
  var tmp = this;
  tmp.descriptor$delegate_1 = lazy(EnumSerializer$descriptor$delegate$lambda(this, serialName));
}
protoOf(EnumSerializer).get_descriptor_wjt6a0_k$ = function () {
  var tmp0 = this.descriptor$delegate_1;
  var tmp = KProperty1;
  // Inline function 'kotlin.getValue' call
  getPropertyCallableRef('descriptor', 1, tmp, EnumSerializer$_get_descriptor_$ref_j67dlw(), null);
  return tmp0.get_value_j01efc_k$();
};
protoOf(EnumSerializer).toString = function () {
  return 'kotlinx.serialization.internal.EnumSerializer<' + this.get_descriptor_wjt6a0_k$().get_serialName_u2rqhk_k$() + '>';
};
function _get_elementDescriptors__y23q9p($this) {
  var tmp0 = $this.elementDescriptors$delegate_1;
  var tmp = KProperty1;
  // Inline function 'kotlin.getValue' call
  getPropertyCallableRef('elementDescriptors', 1, tmp, EnumDescriptor$_get_elementDescriptors_$ref_5lvk4a(), null);
  return tmp0.get_value_j01efc_k$();
}
function EnumDescriptor$elementDescriptors$delegate$lambda($elementsCount, $name, this$0) {
  return function () {
    var tmp = 0;
    var tmp_0 = $elementsCount;
    // Inline function 'kotlin.arrayOfNulls' call
    var tmp_1 = Array(tmp_0);
    while (tmp < tmp_0) {
      var tmp_2 = tmp;
      tmp_1[tmp_2] = buildSerialDescriptor($name + '.' + this$0.getElementName_u4sqmf_k$(tmp_2), OBJECT_getInstance(), []);
      tmp = tmp + 1 | 0;
    }
    return tmp_1;
  };
}
function EnumDescriptor$_get_elementDescriptors_$ref_5lvk4a() {
  return constructCallableReference(function (p0) {
    return _get_elementDescriptors__y23q9p(p0);
  }, 1, 0, 2);
}
function EnumDescriptor(name, elementsCount) {
  PluginGeneratedSerialDescriptor.call(this, name, VOID, elementsCount);
  this.kind_1 = ENUM_getInstance();
  var tmp = this;
  tmp.elementDescriptors$delegate_1 = lazy(EnumDescriptor$elementDescriptors$delegate$lambda(elementsCount, name, this));
}
protoOf(EnumDescriptor).get_kind_wop7ml_k$ = function () {
  return this.kind_1;
};
protoOf(EnumDescriptor).getElementDescriptor_ncda77_k$ = function (index) {
  return getChecked(_get_elementDescriptors__y23q9p(this), index);
};
protoOf(EnumDescriptor).equals = function (other) {
  if (this === other)
    return true;
  if (other == null)
    return false;
  if (!(!(other == null) ? isInterface(other, SerialDescriptor) : false))
    return false;
  if (!(other.get_kind_wop7ml_k$() === ENUM_getInstance()))
    return false;
  if (!(this.get_serialName_u2rqhk_k$() === other.get_serialName_u2rqhk_k$()))
    return false;
  if (!equals(cachedSerialNames(this), cachedSerialNames(other)))
    return false;
  return true;
};
protoOf(EnumDescriptor).toString = function () {
  return joinToString(get_elementNames(this), ', ', this.get_serialName_u2rqhk_k$() + '(', ')');
};
protoOf(EnumDescriptor).hashCode = function () {
  var result = getStringHashCode(this.get_serialName_u2rqhk_k$());
  // Inline function 'kotlinx.serialization.internal.elementsHashCodeBy' call
  // Inline function 'kotlin.collections.fold' call
  var accumulator = 1;
  var _iterator__ex2g4s = get_elementNames(this).iterator_jk1svi_k$();
  while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var element = _iterator__ex2g4s.next_20eer_k$();
    var hash = accumulator;
    var tmp = imul(31, hash);
    // Inline function 'kotlin.hashCode' call
    var tmp1_elvis_lhs = element == null ? null : hashCode(element);
    accumulator = tmp + (tmp1_elvis_lhs == null ? 0 : tmp1_elvis_lhs) | 0;
  }
  var elementsHashCode = accumulator;
  result = imul(31, result) + elementsHashCode | 0;
  return result;
};
function createAnnotatedEnumSerializer(serialName, values, names, entryAnnotations, classAnnotations) {
  var descriptor = new EnumDescriptor(serialName, values.length);
  if (classAnnotations == null)
    null;
  else {
    // Inline function 'kotlin.collections.forEach' call
    var inductionVariable = 0;
    var last = classAnnotations.length;
    while (inductionVariable < last) {
      var element = classAnnotations[inductionVariable];
      inductionVariable = inductionVariable + 1 | 0;
      descriptor.pushClassAnnotation_xtmllk_k$(element);
    }
  }
  // Inline function 'kotlin.collections.forEachIndexed' call
  var index = 0;
  var inductionVariable_0 = 0;
  var last_0 = values.length;
  while (inductionVariable_0 < last_0) {
    var item = values[inductionVariable_0];
    inductionVariable_0 = inductionVariable_0 + 1 | 0;
    var _unary__edvuaz = index;
    index = _unary__edvuaz + 1 | 0;
    var tmp0_elvis_lhs = getOrNull(names, _unary__edvuaz);
    var elementName = tmp0_elvis_lhs == null ? item.name_1 : tmp0_elvis_lhs;
    descriptor.addElement$default_mty55e_k$(elementName);
    var tmp1_safe_receiver = getOrNull(entryAnnotations, _unary__edvuaz);
    if (tmp1_safe_receiver == null)
      null;
    else {
      // Inline function 'kotlin.collections.forEach' call
      var inductionVariable_1 = 0;
      var last_1 = tmp1_safe_receiver.length;
      while (inductionVariable_1 < last_1) {
        var element_0 = tmp1_safe_receiver[inductionVariable_1];
        inductionVariable_1 = inductionVariable_1 + 1 | 0;
        descriptor.pushAnnotation_fm15ho_k$(element_0);
      }
    }
  }
  return EnumSerializer_init_$Create$(serialName, values, descriptor);
}
function get_EMPTY_DESCRIPTOR_ARRAY() {
  _init_properties_Platform_common_kt__3qzecs();
  return EMPTY_DESCRIPTOR_ARRAY;
}
var EMPTY_DESCRIPTOR_ARRAY;
function cachedSerialNames(_this__u8e3s4) {
  _init_properties_Platform_common_kt__3qzecs();
  if (isInterface(_this__u8e3s4, CachedNames))
    return _this__u8e3s4.get_serialNames_8zf3cl_k$();
  var result = HashSet_init_$Create$_0(_this__u8e3s4.get_elementsCount_288r0x_k$());
  var inductionVariable = 0;
  var last = _this__u8e3s4.get_elementsCount_288r0x_k$();
  if (inductionVariable < last)
    do {
      var i = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      // Inline function 'kotlin.collections.plusAssign' call
      var element = _this__u8e3s4.getElementName_u4sqmf_k$(i);
      result.add_utx5q5_k$(element);
    }
     while (inductionVariable < last);
  return result;
}
function compactArray(_this__u8e3s4) {
  _init_properties_Platform_common_kt__3qzecs();
  // Inline function 'kotlin.takeUnless' call
  var tmp;
  // Inline function 'kotlin.collections.isNullOrEmpty' call
  if (!(_this__u8e3s4 == null || _this__u8e3s4.isEmpty_y1axqb_k$())) {
    tmp = _this__u8e3s4;
  } else {
    tmp = null;
  }
  var tmp0_safe_receiver = tmp;
  var tmp_0;
  if (tmp0_safe_receiver == null) {
    tmp_0 = null;
  } else {
    // Inline function 'kotlin.collections.toTypedArray' call
    tmp_0 = copyToArray(tmp0_safe_receiver);
  }
  var tmp1_elvis_lhs = tmp_0;
  return tmp1_elvis_lhs == null ? get_EMPTY_DESCRIPTOR_ARRAY() : tmp1_elvis_lhs;
}
var properties_initialized_Platform_common_kt_i7q4ty;
function _init_properties_Platform_common_kt__3qzecs() {
  if (!properties_initialized_Platform_common_kt_i7q4ty) {
    properties_initialized_Platform_common_kt_i7q4ty = true;
    // Inline function 'kotlin.arrayOf' call
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    EMPTY_DESCRIPTOR_ARRAY = [];
  }
}
function _get_childSerializers__7vnyfa($this) {
  var tmp0 = $this.childSerializers$delegate_1;
  var tmp = KProperty1;
  // Inline function 'kotlin.getValue' call
  getPropertyCallableRef('childSerializers', 1, tmp, PluginGeneratedSerialDescriptor$_get_childSerializers_$ref_e7suca(), null);
  return tmp0.get_value_j01efc_k$();
}
function _get__hashCode__tgwhef_0($this) {
  var tmp0 = $this._hashCode$delegate_1;
  var tmp = KProperty1;
  // Inline function 'kotlin.getValue' call
  getPropertyCallableRef('_hashCode', 1, tmp, PluginGeneratedSerialDescriptor$_get__hashCode_$ref_cmj4vz(), null);
  return tmp0.get_value_j01efc_k$();
}
function buildIndices($this) {
  var indices = HashMap_init_$Create$();
  var inductionVariable = 0;
  var last = $this.names_1.length - 1 | 0;
  if (inductionVariable <= last)
    do {
      var i = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      // Inline function 'kotlin.collections.set' call
      var key = $this.names_1[i];
      indices.put_4fpzoq_k$(key, i);
    }
     while (inductionVariable <= last);
  return indices;
}
function PluginGeneratedSerialDescriptor$childSerializers$delegate$lambda(this$0) {
  return function () {
    var tmp0_safe_receiver = this$0.generatedSerializer_1;
    var tmp1_elvis_lhs = tmp0_safe_receiver == null ? null : tmp0_safe_receiver.childSerializers_5ghqw5_k$();
    return tmp1_elvis_lhs == null ? get_EMPTY_SERIALIZER_ARRAY() : tmp1_elvis_lhs;
  };
}
function PluginGeneratedSerialDescriptor$_get_childSerializers_$ref_e7suca() {
  return constructCallableReference(function (p0) {
    return _get_childSerializers__7vnyfa(p0);
  }, 1, 0, 3);
}
function PluginGeneratedSerialDescriptor$typeParameterDescriptors$delegate$lambda(this$0) {
  return function () {
    var tmp0_safe_receiver = this$0.generatedSerializer_1;
    var tmp1_safe_receiver = tmp0_safe_receiver == null ? null : tmp0_safe_receiver.typeParametersSerializers_fr94fx_k$();
    var tmp;
    if (tmp1_safe_receiver == null) {
      tmp = null;
    } else {
      // Inline function 'kotlin.collections.map' call
      // Inline function 'kotlin.collections.mapTo' call
      var destination = ArrayList_init_$Create$_0(tmp1_safe_receiver.length);
      var inductionVariable = 0;
      var last = tmp1_safe_receiver.length;
      while (inductionVariable < last) {
        var item = tmp1_safe_receiver[inductionVariable];
        inductionVariable = inductionVariable + 1 | 0;
        var tmp$ret$2 = item.get_descriptor_wjt6a0_k$();
        destination.add_utx5q5_k$(tmp$ret$2);
      }
      tmp = destination;
    }
    return compactArray(tmp);
  };
}
function PluginGeneratedSerialDescriptor$_get_typeParameterDescriptors_$ref_jk3pka() {
  return constructCallableReference(function (p0) {
    return p0.get_typeParameterDescriptors_3o94ow_k$();
  }, 1, 0, 4);
}
function PluginGeneratedSerialDescriptor$_hashCode$delegate$lambda(this$0) {
  return function () {
    return hashCodeImpl(this$0, this$0.get_typeParameterDescriptors_3o94ow_k$());
  };
}
function PluginGeneratedSerialDescriptor$_get__hashCode_$ref_cmj4vz() {
  return constructCallableReference(function (p0) {
    return _get__hashCode__tgwhef_0(p0);
  }, 1, 0, 5);
}
function PluginGeneratedSerialDescriptor(serialName, generatedSerializer, elementsCount) {
  generatedSerializer = generatedSerializer === VOID ? null : generatedSerializer;
  this.serialName_1 = serialName;
  this.generatedSerializer_1 = generatedSerializer;
  this.elementsCount_1 = elementsCount;
  this.added_1 = -1;
  var tmp = this;
  var tmp_0 = 0;
  var tmp_1 = this.elementsCount_1;
  // Inline function 'kotlin.arrayOfNulls' call
  var tmp_2 = Array(tmp_1);
  while (tmp_0 < tmp_1) {
    tmp_2[tmp_0] = '[UNINITIALIZED]';
    tmp_0 = tmp_0 + 1 | 0;
  }
  tmp.names_1 = tmp_2;
  var tmp_3 = this;
  // Inline function 'kotlin.arrayOfNulls' call
  var size = this.elementsCount_1;
  tmp_3.propertiesAnnotations_1 = Array(size);
  this.classAnnotations_1 = null;
  this.elementsOptionality_1 = booleanArray(this.elementsCount_1);
  this.indices_1 = emptyMap();
  var tmp_4 = this;
  var tmp_5 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  tmp_4.childSerializers$delegate_1 = lazy_0(tmp_5, PluginGeneratedSerialDescriptor$childSerializers$delegate$lambda(this));
  var tmp_6 = this;
  var tmp_7 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  tmp_6.typeParameterDescriptors$delegate_1 = lazy_0(tmp_7, PluginGeneratedSerialDescriptor$typeParameterDescriptors$delegate$lambda(this));
  var tmp_8 = this;
  var tmp_9 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  tmp_8._hashCode$delegate_1 = lazy_0(tmp_9, PluginGeneratedSerialDescriptor$_hashCode$delegate$lambda(this));
}
protoOf(PluginGeneratedSerialDescriptor).get_serialName_u2rqhk_k$ = function () {
  return this.serialName_1;
};
protoOf(PluginGeneratedSerialDescriptor).get_elementsCount_288r0x_k$ = function () {
  return this.elementsCount_1;
};
protoOf(PluginGeneratedSerialDescriptor).get_kind_wop7ml_k$ = function () {
  return CLASS_getInstance();
};
protoOf(PluginGeneratedSerialDescriptor).get_serialNames_8zf3cl_k$ = function () {
  return this.indices_1.get_keys_wop4xp_k$();
};
protoOf(PluginGeneratedSerialDescriptor).get_typeParameterDescriptors_3o94ow_k$ = function () {
  var tmp0 = this.typeParameterDescriptors$delegate_1;
  var tmp = KProperty1;
  // Inline function 'kotlin.getValue' call
  getPropertyCallableRef('typeParameterDescriptors', 1, tmp, PluginGeneratedSerialDescriptor$_get_typeParameterDescriptors_$ref_jk3pka(), null);
  return tmp0.get_value_j01efc_k$();
};
protoOf(PluginGeneratedSerialDescriptor).addElement_5pzumi_k$ = function (name, isOptional) {
  this.added_1 = this.added_1 + 1 | 0;
  this.names_1[this.added_1] = name;
  this.elementsOptionality_1[this.added_1] = isOptional;
  this.propertiesAnnotations_1[this.added_1] = null;
  if (this.added_1 === (this.elementsCount_1 - 1 | 0)) {
    this.indices_1 = buildIndices(this);
  }
};
protoOf(PluginGeneratedSerialDescriptor).addElement$default_mty55e_k$ = function (name, isOptional, $super) {
  isOptional = isOptional === VOID ? false : isOptional;
  var tmp;
  if ($super === VOID) {
    this.addElement_5pzumi_k$(name, isOptional);
    tmp = Unit_instance;
  } else {
    tmp = $super.addElement_5pzumi_k$.call(this, name, isOptional);
  }
  return tmp;
};
protoOf(PluginGeneratedSerialDescriptor).pushAnnotation_fm15ho_k$ = function (annotation) {
  // Inline function 'kotlin.let' call
  var it = this.propertiesAnnotations_1[this.added_1];
  var tmp;
  if (it == null) {
    var result = ArrayList_init_$Create$_0(1);
    this.propertiesAnnotations_1[this.added_1] = result;
    tmp = result;
  } else {
    tmp = it;
  }
  var list = tmp;
  list.add_utx5q5_k$(annotation);
};
protoOf(PluginGeneratedSerialDescriptor).pushClassAnnotation_xtmllk_k$ = function (a) {
  if (this.classAnnotations_1 == null) {
    this.classAnnotations_1 = ArrayList_init_$Create$_0(1);
  }
  ensureNotNull(this.classAnnotations_1).add_utx5q5_k$(a);
};
protoOf(PluginGeneratedSerialDescriptor).getElementDescriptor_ncda77_k$ = function (index) {
  return getChecked(_get_childSerializers__7vnyfa(this), index).get_descriptor_wjt6a0_k$();
};
protoOf(PluginGeneratedSerialDescriptor).getElementName_u4sqmf_k$ = function (index) {
  return getChecked(this.names_1, index);
};
protoOf(PluginGeneratedSerialDescriptor).getElementIndex_utfbym_k$ = function (name) {
  var tmp0_elvis_lhs = this.indices_1.get_wei43m_k$(name);
  return tmp0_elvis_lhs == null ? -3 : tmp0_elvis_lhs;
};
protoOf(PluginGeneratedSerialDescriptor).equals = function (other) {
  var tmp$ret$0;
  $l$block_5: {
    // Inline function 'kotlinx.serialization.internal.equalsImpl' call
    if (this === other) {
      tmp$ret$0 = true;
      break $l$block_5;
    }
    if (!(other instanceof PluginGeneratedSerialDescriptor)) {
      tmp$ret$0 = false;
      break $l$block_5;
    }
    if (!(this.get_serialName_u2rqhk_k$() === other.get_serialName_u2rqhk_k$())) {
      tmp$ret$0 = false;
      break $l$block_5;
    }
    if (!contentEquals(this.get_typeParameterDescriptors_3o94ow_k$(), other.get_typeParameterDescriptors_3o94ow_k$())) {
      tmp$ret$0 = false;
      break $l$block_5;
    }
    if (!(this.get_elementsCount_288r0x_k$() === other.get_elementsCount_288r0x_k$())) {
      tmp$ret$0 = false;
      break $l$block_5;
    }
    var inductionVariable = 0;
    var last = this.get_elementsCount_288r0x_k$();
    if (inductionVariable < last)
      do {
        var index = inductionVariable;
        inductionVariable = inductionVariable + 1 | 0;
        if (!(this.getElementDescriptor_ncda77_k$(index).get_serialName_u2rqhk_k$() === other.getElementDescriptor_ncda77_k$(index).get_serialName_u2rqhk_k$())) {
          tmp$ret$0 = false;
          break $l$block_5;
        }
        if (!equals(this.getElementDescriptor_ncda77_k$(index).get_kind_wop7ml_k$(), other.getElementDescriptor_ncda77_k$(index).get_kind_wop7ml_k$())) {
          tmp$ret$0 = false;
          break $l$block_5;
        }
      }
       while (inductionVariable < last);
    tmp$ret$0 = true;
  }
  return tmp$ret$0;
};
protoOf(PluginGeneratedSerialDescriptor).hashCode = function () {
  return _get__hashCode__tgwhef_0(this);
};
protoOf(PluginGeneratedSerialDescriptor).toString = function () {
  return toStringImpl(this);
};
function hashCodeImpl(_this__u8e3s4, typeParams) {
  var result = getStringHashCode(_this__u8e3s4.get_serialName_u2rqhk_k$());
  result = imul(31, result) + contentHashCode(typeParams) | 0;
  var elementDescriptors = get_elementDescriptors(_this__u8e3s4);
  // Inline function 'kotlinx.serialization.internal.elementsHashCodeBy' call
  // Inline function 'kotlin.collections.fold' call
  var accumulator = 1;
  var _iterator__ex2g4s = elementDescriptors.iterator_jk1svi_k$();
  while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var element = _iterator__ex2g4s.next_20eer_k$();
    var hash = accumulator;
    var tmp = imul(31, hash);
    // Inline function 'kotlin.hashCode' call
    var tmp0_safe_receiver = element.get_serialName_u2rqhk_k$();
    var tmp1_elvis_lhs = tmp0_safe_receiver == null ? null : hashCode(tmp0_safe_receiver);
    accumulator = tmp + (tmp1_elvis_lhs == null ? 0 : tmp1_elvis_lhs) | 0;
  }
  var namesHash = accumulator;
  // Inline function 'kotlinx.serialization.internal.elementsHashCodeBy' call
  // Inline function 'kotlin.collections.fold' call
  var accumulator_0 = 1;
  var _iterator__ex2g4s_0 = elementDescriptors.iterator_jk1svi_k$();
  while (_iterator__ex2g4s_0.hasNext_bitz1p_k$()) {
    var element_0 = _iterator__ex2g4s_0.next_20eer_k$();
    var hash_0 = accumulator_0;
    var tmp_0 = imul(31, hash_0);
    // Inline function 'kotlin.hashCode' call
    var tmp0_safe_receiver_0 = element_0.get_kind_wop7ml_k$();
    var tmp1_elvis_lhs_0 = tmp0_safe_receiver_0 == null ? null : hashCode(tmp0_safe_receiver_0);
    accumulator_0 = tmp_0 + (tmp1_elvis_lhs_0 == null ? 0 : tmp1_elvis_lhs_0) | 0;
  }
  var kindHash = accumulator_0;
  result = imul(31, result) + namesHash | 0;
  result = imul(31, result) + kindHash | 0;
  return result;
}
function toStringImpl(_this__u8e3s4) {
  var tmp = until(0, _this__u8e3s4.get_elementsCount_288r0x_k$());
  var tmp_0 = _this__u8e3s4.get_serialName_u2rqhk_k$() + '(';
  return joinToString(tmp, ', ', tmp_0, ')', VOID, VOID, toStringImpl$lambda(_this__u8e3s4));
}
function toStringImpl$lambda($this_toStringImpl) {
  return function (i) {
    return $this_toStringImpl.getElementName_u4sqmf_k$(i) + ': ' + $this_toStringImpl.getElementDescriptor_ncda77_k$(i).get_serialName_u2rqhk_k$();
  };
}
function get_EMPTY_SERIALIZER_ARRAY() {
  _init_properties_PluginHelperInterfaces_kt__xgvzfp();
  return EMPTY_SERIALIZER_ARRAY;
}
var EMPTY_SERIALIZER_ARRAY;
function GeneratedSerializer() {
}
var properties_initialized_PluginHelperInterfaces_kt_ap8in1;
function _init_properties_PluginHelperInterfaces_kt__xgvzfp() {
  if (!properties_initialized_PluginHelperInterfaces_kt_ap8in1) {
    properties_initialized_PluginHelperInterfaces_kt_ap8in1 = true;
    // Inline function 'kotlin.arrayOf' call
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    EMPTY_SERIALIZER_ARRAY = [];
  }
}
function SerializableWith() {
}
function getChecked(_this__u8e3s4, index) {
  if (!(0 <= index ? index <= (_this__u8e3s4.length - 1 | 0) : false))
    throw IndexOutOfBoundsException_init_$Create$('Index ' + index + ' out of bounds ' + get_indices(_this__u8e3s4).toString());
  return _this__u8e3s4[index];
}
//region block: exports
export {
  SerializableWith as SerializableWithd2dap36updxd,
  createAnnotatedEnumSerializer as createAnnotatedEnumSerializer20ay4pme9p2h9,
  createSimpleEnumSerializer as createSimpleEnumSerializer2guioz11kk1m0,
};
//endregion


