package com.furkan.userme

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Sayisal alanlari hem sayi (41.0) hem tirnakli string ("41.0") olarak kabul eder;
 * bos string ve gecersiz deger null olur. Cikista her zaman sayi yazilir.
 *
 * Gerekce: mevcut istemciler koordinatlari string olarak gonderiyor.
 */
internal object LenientDoubleSerializer : KSerializer<Double?> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.furkan.userme.LenientDouble", PrimitiveKind.DOUBLE).nullable

    override fun deserialize(decoder: Decoder): Double? {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeDouble()
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.content.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Double?) {
        if (value == null) encoder.encodeNull() else encoder.encodeDouble(value)
    }
}
