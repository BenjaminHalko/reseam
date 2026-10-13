// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.patch

import app.reseam.patch.dex.DexClass
import app.reseam.patch.dex.Opcode
import app.reseam.patch.dex.fieldRef
import app.reseam.patch.dex.methodRef
import app.reseam.patch.dex.opcode
import app.reseam.patch.dex.regA
import app.reseam.patch.dex.stringValue
import app.reseam.patch.dex.typeRef
import app.reseam.patch.types.FieldRef

private const val ENUM = "Ljava/lang/Enum;"

/**
 * The constants an enum's `<clinit>` constructs, by the name each passes to the constructor. A
 * constant is a new instance of the enum, or of its subclass for a constant with a body. Any
 * constructor in that hierarchy may initialize it: optimizers inline an enum's own constructor down
 * to `Enum.<init>(String, int)`.
 */
internal fun readEnumConstants(classDef: DexClass, index: SearchIndex): Map<String, FieldRef> {
    if (classDef.superclass != ENUM) return emptyMap()
    val type = classDef.descriptor
    val initializer = index.methodsInClass(type, "<clinit>").singleOrNull() ?: return emptyMap()
    val insns = initializer.instructions
    val origins = ArgumentOrigins(initializer)
    fun sites(found: ArgumentOrigins.Origins?) =
        found?.takeUnless { it.unknown || it.entry || it.sites.isEmpty() }?.sites
    fun createsConstant(site: Int): Boolean {
        val created =
            insns[site].takeIf { it.opcode == Opcode.NEW_INSTANCE }?.typeRef ?: return false
        return created == type || index.classFor(created)?.superclass == type
    }

    val named = mutableMapOf<Set<Int>, String>()
    insns.forEachIndexed { at, insn ->
        val ref = insn.methodRef ?: return@forEachIndexed
        if (insn.opcode !in constructorCalls || ref.name != "<init>") return@forEachIndexed
        val instance =
            sites(origins.argument(insns, at, 0))?.takeIf { it.all(::createsConstant) }
                ?: return@forEachIndexed
        named[instance] =
            sites(origins.argument(insns, at, 1))
                ?.map { insns[it].stringValue ?: return@forEachIndexed }
                ?.distinct()
                ?.singleOrNull() ?: return@forEachIndexed
    }
    return buildMap {
        insns.forEachIndexed { at, insn ->
            val field = insn.fieldRef ?: return@forEachIndexed
            if (
                insn.opcode != Opcode.SPUT_OBJECT ||
                    field.definingClass != type ||
                    field.fieldType != type
            )
                return@forEachIndexed
            val instance = sites(origins.register(insns, at, insn.regA ?: return@forEachIndexed))
            named[instance]?.let { put(it, field) }
        }
    }
}

private val constructorCalls = setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_DIRECT_RANGE)
