/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.utils

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.util.InspectionMessage
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.NlsContexts.Tooltip
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil.pluralize
import com.intellij.psi.PsiElement
import com.intellij.util.ThreeState
import com.intellij.xml.util.XmlStringUtil.escapeString
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.cargo.toolchain.RustChannel
import org.rust.cargo.toolchain.impl.RustcVersion
import org.rust.ide.annotator.RsAnnotationHolder
import org.rust.ide.annotator.RsErrorAnnotator
import org.rust.ide.annotator.fixes.*
import org.rust.ide.inspections.RsExperimentalChecksInspection
import org.rust.ide.inspections.RsProblemsHolder
import org.rust.ide.inspections.RsTypeCheckInspection
import org.rust.ide.inspections.RsWrongAssocTypeArgumentsInspection
import org.rust.ide.inspections.fixes.*
import org.rust.ide.presentation.render
import org.rust.ide.presentation.renderInsertionSafe
import org.rust.ide.presentation.shortPresentableText
import org.rust.ide.refactoring.implementMembers.ImplementMembersFix
import org.rust.ide.utils.checkMatch.Pattern
import org.rust.ide.utils.import.RsImportHelper.getTypeReferencesInfoFromTys
import org.rust.lang.core.CompilerFeature.Companion.ABI_AMDGPU_KERNEL
import org.rust.lang.core.CompilerFeature.Companion.ABI_AVR_INTERRUPT
import org.rust.lang.core.CompilerFeature.Companion.ABI_C_CMSE_NONSECURE_CALL
import org.rust.lang.core.CompilerFeature.Companion.ABI_EFIAPI
import org.rust.lang.core.CompilerFeature.Companion.ABI_MSP430_INTERRUPT
import org.rust.lang.core.CompilerFeature.Companion.ABI_PTX
import org.rust.lang.core.CompilerFeature.Companion.ABI_THISCALL
import org.rust.lang.core.CompilerFeature.Companion.ABI_UNADJUSTED
import org.rust.lang.core.CompilerFeature.Companion.ABI_VECTORCALL
import org.rust.lang.core.CompilerFeature.Companion.ABI_X86_INTERRUPT
import org.rust.lang.core.CompilerFeature.Companion.C_UNWIND
import org.rust.lang.core.CompilerFeature.Companion.INTRINSICS
import org.rust.lang.core.CompilerFeature.Companion.PLATFORM_INTRINSICS
import org.rust.lang.core.CompilerFeature.Companion.UNBOXED_CLOSURES
import org.rust.lang.core.CompilerFeature.Companion.WASM_ABI
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.ImplLookup
import org.rust.lang.core.resolve.KnownItems
import org.rust.lang.core.resolve.Namespace
import org.rust.lang.core.types.*
import org.rust.lang.core.types.infer.*
import org.rust.lang.core.types.ty.*
import org.rust.lang.utils.RsErrorCode.*
import org.rust.lang.utils.Severity.*
import org.rust.stdext.buildList
import org.rust.stdext.buildMap

private val REF_STR_TY = TyReference(TyStr.INSTANCE, Mutability.IMMUTABLE)
private val MUT_REF_STR_TY = TyReference(TyStr.INSTANCE, Mutability.MUTABLE)

sealed class RsDiagnostic(
    val element: PsiElement,
    val endElement: PsiElement? = null,
    val inspectionClass: Class<*> = RsErrorAnnotator::class.java
) {
    abstract fun prepare(): PreparedAnnotation

    class TypeError(
        element: PsiElement,
        private val expectedTy: Ty,
        private val actualTy: Ty
    ) : RsDiagnostic(element, inspectionClass = RsTypeCheckInspection::class.java), TypeFoldable<TypeError> {
        private val description: String? = if (expectedTy.hasTyInfer || actualTy.hasTyInfer) {
            // if types contain infer types, they will be replaced with more specific (e.g. `{integer}` with `i32`)
            // so we capture string representation of types right here
            expectedFound(element, expectedTy, actualTy)
        } else {
            null
        }

        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                E0308,
                "mismatched types",
                description ?: expectedFound(element, expectedTy, actualTy),
                fixes = buildList {
                    if (element is RsElement) {
                        if (element is RsExpr) {
                            addAll(createExprQuickFixes(element))
                        }

                        val retFix = ChangeReturnTypeFix.createIfCompatible(element, actualTy)
                        if (retFix != null) {
                            add(retFix)
                        }
                    }

                    val parent = element.parent
                    if (parent is RsLetDecl && parent.typeReference != null) {
                        val pat = parent.pat
                        if (pat is RsPatIdent &&
                            !actualTy.containsTyOfClass(TyUnknown::class.java, TyAnon::class.java)) {
                            val text = "Change type of `${pat.patBinding.identifier.text ?: "?"}` to `${actualTy.renderInsertionSafe()}`"
                            add(ConvertLetDeclTypeFix(parent, text, actualTy))
                        }
                    }
                }
            )
        }

        private fun createExprQuickFixes(element: RsExpr): List<LocalQuickFix> {
            return buildList {
                if (expectedTy is TyNumeric && isActualTyNumeric()) {
                    add(AddAsTyFix(element, expectedTy))
                } else {
                    val (lookup, items) = element.implLookupAndKnownItems
                    if (isFromActualImplForExpected(items, lookup)) {
                        add(ConvertToTyUsingFromTraitFix(element, expectedTy))
                    } else { // only check TryFrom if From is not available
                        val resultErrTy = errTyOfTryFromActualImplForTy(expectedTy, items, lookup)
                        if (resultErrTy != null) {
                            add(ConvertToTyUsingTryFromTraitAndUnpackFix(element, expectedTy, resultErrTy))
                        }
                    }
                    // currently it's possible to have `impl FromStr` independently form `impl<'a> From<&'a str>` or
                    // `impl<'a> TryFrom<&'a str>`, so check for FromStr even if From or TryFrom is available
                    val resultFromStrErrTy = ifActualIsStrGetErrTyOfFromStrImplForTy(expectedTy, items, lookup)
                    if (resultFromStrErrTy != null) {
                        add(ConvertToTyUsingFromStrAndUnpackFix(element, expectedTy, resultFromStrErrTy))
                    }
                    if (isToOwnedImplWithExpectedForActual(items, lookup)) {
                        add(ConvertToOwnedTyFix(element, expectedTy))
                    }
                    val stringTy = items.String.asTy()
                    if (expectedTy.isEquivalentTo(stringTy)
                        && (isToStringImplForActual(items, lookup) || isActualTyNumeric())) {
                        add(ConvertToStringFix(element))
                    } else if (expectedTy is TyReference) {
                        if (expectedTy.mutability == Mutability.IMMUTABLE) {
                            if (isTraitWithTySubstImplForActual(lookup, items.Borrow, expectedTy)) {
                                add(ConvertToBorrowedTyFix(element, expectedTy))
                            }
                            if (isTraitWithTySubstImplForActual(lookup, items.AsRef, expectedTy)) {
                                add(ConvertToRefTyFix(element, expectedTy))
                            }
                        } else if (expectedTy.mutability == Mutability.MUTABLE) {
                            if (actualTy is TyReference && actualTy.mutability == Mutability.IMMUTABLE) {
                                add(ChangeRefToMutableFix(element))
                            }

                            if (element.isMutable && lookup.coercionSequence(actualTy).all { it !is TyReference || it.mutability.isMut }) {
                                if (isTraitWithTySubstImplForActual(lookup, items.BorrowMut, expectedTy)) {
                                    add(ConvertToBorrowedTyWithMutFix(element, expectedTy))
                                }
                                if (isTraitWithTySubstImplForActual(lookup, items.AsMut, expectedTy)) {
                                    add(ConvertToMutTyFix(element, expectedTy))
                                }
                            }
                        }
                    } else if (expectedTy is TyAdt && expectedTy.item == items.Result) {
                        val (expOkTy, expErrTy) = expectedTy.typeArguments
                        if (expErrTy.isEquivalentTo(errTyOfTryFromActualImplForTy(expOkTy, items, lookup))) {
                            add(ConvertToTyUsingTryFromTraitFix(element, expOkTy))
                        }
                        if (expErrTy.isEquivalentTo(ifActualIsStrGetErrTyOfFromStrImplForTy(expOkTy, items, lookup))) {
                            add(ConvertToTyUsingFromStrFix(element, expOkTy))
                        }
                    }
                    if (actualTy.isEquivalentTo(stringTy)) {
                        if (expectedTy.isEquivalentTo(REF_STR_TY)) {
                            add(ConvertToImmutableStrFix(element))
                        } else if (expectedTy.isEquivalentTo(MUT_REF_STR_TY)) {
                            add(ConvertToMutStrFix(element))
                        }
                    }

                    val derefsRefsToExpected = derefRefPathFromActualToExpected(lookup, element)
                    if (derefsRefsToExpected != null) {
                        add(ConvertToTyWithDerefsRefsFix(element, expectedTy, derefsRefsToExpected))
                    }
                }
            }
        }

        private fun isActualTyNumeric() = actualTy is TyNumeric || actualTy is TyInfer.IntVar || actualTy is TyInfer.FloatVar

        private fun isFromActualImplForExpected(items: KnownItems, lookup: ImplLookup): Boolean {
            val fromTrait = items.From ?: return false
            return lookup.canSelect(TraitRef(expectedTy, fromTrait.withSubst(actualTy)))
        }

        private fun errTyOfTryFromActualImplForTy(ty: Ty, items: KnownItems, lookup: ImplLookup): Ty? {
            val fromTrait = items.TryFrom ?: return null
            val result = lookup.selectProjectionStrict(TraitRef(ty, fromTrait.withSubst(actualTy)),
                fromTrait.associatedTypesTransitively.find { it.name == "Error" } ?: return null)
            return result.ok()?.value
        }

        private fun ifActualIsStrGetErrTyOfFromStrImplForTy(ty: Ty, items: KnownItems, lookup: ImplLookup): Ty? {
            if (lookup.coercionSequence(actualTy).lastOrNull() !is TyStr) return null
            val fromStr = items.FromStr ?: return null
            val result = lookup.selectProjectionStrict(
                TraitRef(ty, BoundElement(fromStr)),
                fromStr.findAssociatedType("Err") ?: return null
            )
            return result.ok()?.value
        }

        private fun isToOwnedImplWithExpectedForActual(items: KnownItems, lookup: ImplLookup): Boolean {
            val toOwnedTrait = items.ToOwned ?: return false
            val result = lookup.selectProjectionStrictWithDeref(
                TraitRef(actualTy, BoundElement(toOwnedTrait)),
                toOwnedTrait.findAssociatedType("Owned") ?: return false
            )
            return expectedTy.isEquivalentTo(result.ok()?.value)
        }

        private fun isToStringImplForActual(items: KnownItems, lookup: ImplLookup): Boolean {
            val toStringTrait = items.ToString ?: return false
            return lookup.canSelectWithDeref(TraitRef(actualTy, BoundElement(toStringTrait)))
        }

        private fun isTraitWithTySubstImplForActual(lookup: ImplLookup, trait: RsTraitItem?, ty: TyReference): Boolean =
            trait != null && lookup.canSelectWithDeref(TraitRef(actualTy, trait.withSubst(ty.referenced)))

        private fun expectedFound(element: PsiElement, expectedTy: Ty, actualTy: Ty): String {
            val useQualifiedName = getConflictingNames(element, expectedTy, actualTy)
            return "expected `${expectedTy.rendered(useQualifiedName)}`, found `${actualTy.rendered(useQualifiedName)}`"
        }

        /**
         * Try to find a "path" from [actualTy] to [expectedTy] through dereferences and references.
         *
         * The method works by getting coercion sequence of types for the [actualTy] and sequence of the types that can
         * lead to [expectedTy] by adding references. The "expected sequence" is represented by a list of references'
         * mutabilities, and a map from the type X to the index i in the list such that if we apply the references from
         * 0 (inclusive) to i (exclusive) we will get [expectedTy]. For example for [expectedTy] = `&mut&i32` we will
         * have: [mutable, immutable] and {&mut&i32 -> 0, &i32 -> 1, i32 -> 2}. Then, using those data structures, we
         * try to find a first type X of the "actual" sequence that is also in the "expected" sequence, and a number of
         * dereferences to get from [actualTy] to X and references to get from X to [expectedTy]. Finally we try to
         * check if applying the references would agree with the expression's mutability.
         */
        private fun derefRefPathFromActualToExpected(lookup: ImplLookup, element: RsElement): DerefRefPath? {
            // get all the types that can lead to `expectedTy` by adding references to them
            val expectedRefSeq: MutableList<Mutability> = mutableListOf()
            val tyToExpectedRefSeq: Map<Ty, Int> = buildMap {
                put(expectedTy, 0)
                var ty = expectedTy
                var i = 1
                while (ty is TyReference) {
                    expectedRefSeq.add(ty.mutability)
                    ty = ty.referenced
                    put(ty, i++)
                }
            }
            // get all the types we can get by dereferencing `actualTy`
            val actualCoercionSeq = lookup.coercionSequence(actualTy).toList()
            var refSeqEnd: Int? = null
            // for the first type X in the "actual sequence" that is also in the "expected sequence"; get the number of
            // dereferences we need to apply to get to X from `actualTy` and number of references to get to `expectedTy`
            val derefs = actualCoercionSeq.indexOfFirst { refSeqEnd = tyToExpectedRefSeq[it]; refSeqEnd != null }
            val refs = expectedRefSeq.subList(0, refSeqEnd ?: return null)
            // check that mutability of references would not contradict the `element`
            val isSuitableMutability = refs.isEmpty() ||
                !refs.last().isMut ||
                element is RsExpr &&
                element.isMutable &&
                // covers cases like `let mut x: &T = ...`
                actualCoercionSeq.subList(0, derefs + 1).all {
                    it !is TyReference || it.mutability.isMut
                }
            if (!isSuitableMutability) return null
            return DerefRefPath(derefs, refs)
        }

        override fun superFoldWith(folder: TypeFolder): TypeError =
            TypeError(element, expectedTy.foldWith(folder), actualTy.foldWith(folder))

        override fun superVisitWith(visitor: TypeVisitor): Boolean =
            expectedTy.visitWith(visitor) || actualTy.visitWith(visitor)
    }

    class DerefError(
        element: PsiElement,
        val ty: Ty
    ) : RsDiagnostic(element, inspectionClass = RsExperimentalChecksInspection::class.java) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0614,
            "type ${ty.rendered(getConflictingNames(element, ty))} cannot be dereferenced"
        )
    }

    class AccessError(
        element: PsiElement,
        private val errorCode: RsErrorCode,
        private val itemType: String,
        private val fix: MakePublicFix?
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            errorCode,
            "$itemType `${element.text}` is private",
            fixes = listOfNotNull(fix)
        )
    }

    class StructFieldAccessError(
        element: PsiElement,
        private val fieldName: String,
        private val structName: String,
        private val fix: MakePublicFix?
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            if (element.parent is RsStructLiteralField) E0451 else E0616,
            "Field `$fieldName` of struct `$structName` is private",
            fixes = listOfNotNull(fix)
        )
    }

    class UnsafeError(
        element: RsExpr,
        private val message: String
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0133,
            message,
            fixes = listOfNotNull(SurroundWithUnsafeFix(element as RsExpr), AddUnsafeFix.create(element))
        )
    }

    class TypePlaceholderForbiddenError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0121,
            "The type placeholder `_` is not allowed within types on item signatures"
        )
    }

    class ImplDropForNonAdtError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0120,
            "Drop can be only implemented by structs and enums"
        )
    }

    class SelfInStaticMethodError(
        element: PsiElement,
        private val function: RsFunction
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation {
            val fixes = mutableListOf<LocalQuickFix>()
            if (function.owner.isImplOrTrait) fixes.add(AddSelfFix(function))
            return PreparedAnnotation(
                ERROR,
                E0424,
                "The self keyword was used in a static method",
                fixes = fixes
            )
        }
    }

    class UnnecessaryVisibilityQualifierError(
        element: RsVis
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0449,
            "Unnecessary visibility qualifier",
            fixes = listOf(RemoveElementFix(element, "visibility qualifier"))
        )
    }

    class UnsafeNegativeImplementationError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0198,
            "Negative implementations are not unsafe"
        )
    }

    class UnsafeTraitImplError(
        element: PsiElement,
        private val traitName: String
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0199,
            errorText()
        )

        private fun errorText(): String {
            return "Implementing the trait `$traitName` is not unsafe"
        }
    }

    class TraitMissingUnsafeImplError(
        element: PsiElement,
        private val traitName: String
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0200,
            errorText(),
            fixes = listOfNotNull(AddUnsafeFix.create(element))
        )

        private fun errorText(): String {
            return "The trait `$traitName` requires an `unsafe impl` declaration"
        }
    }

    class IncorrectlyPlacedInlineAttr(
        element: PsiElement,
        private val attr: RsAttr
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0518,
            "Attribute should be applied to function or closure",
            fixes = listOf(RemoveAttrFix(attr))
        )
    }

    class TraitMissingUnsafeImplAttributeError(
        element: PsiElement,
        private val attrRequiringUnsafeImpl: String
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0569,
            errorText()
        )

        private fun errorText(): String {
            return "Requires an `unsafe impl` declaration due to `#[$attrRequiringUnsafeImpl]` attribute"
        }
    }

    class UnknownMemberInTraitError(
        element: PsiElement,
        private val member: RsAbstractable,
        private val traitName: String
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            errorCode(),
            errorText(),
            fixes = listOfNotNull(AddDefinitionToTraitFix.createIfCompatible(member))
        )

        private fun errorCode(): RsErrorCode =
            when (member) {
                is RsTypeAlias -> E0437
                is RsConstant -> E0438
                else -> E0407
            }

        private fun errorText(): String {
            val itemType = when (member) {
                is RsTypeAlias -> "Type"
                is RsConstant -> "Const"
                else -> "Method"
            }
            return "$itemType `${member.name}` is not a member of trait `$traitName`"
        }
    }

    class ImplBothCopyAndDropError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0184,
            "Cannot implement both Copy and Drop"
        )
    }

    class DeclMissingFromTraitError(
        element: PsiElement,
        private val fn: RsFunction,
        private val selfParameter: RsSelfParameter
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0185,
            errorText()
        )

        private fun errorText(): String {
            return "Method `${fn.name}` has a `${selfParameter.canonicalDecl}` declaration in the impl, but not in the trait"
        }
    }

    class DeclMissingFromImplError(
        element: PsiElement,
        private val fn: RsFunction,
        private val selfParameter: RsSelfParameter?
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0186,
            errorText()
        )

        private fun errorText(): String {
            return "Method `${fn.name}` has a `${selfParameter?.canonicalDecl}` declaration in the trait, but not in the impl"
        }
    }

    class ExplicitCallToDrop(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0040,
            errorText(),
            fixes = listOf(ReplaceWithStdMemDropFix(element.parent)) // e.parent: fn's name -> RsMethodCall or RsCallExpr
        )

        private fun errorText(): String {
            return "Explicit calls to `drop` are forbidden. Use `std::mem::drop` instead"
        }
    }

    class TraitParamCountMismatchError(
        element: PsiElement,
        private val fn: RsFunction,
        private val traitName: String,
        private val paramsCount: Int,
        private val superParamsCount: Int
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0050,
            errorText()
        )

        private fun errorText(): String {
            return "Method `${fn.name}` has $paramsCount ${pluralize("parameter", paramsCount)} but the declaration in trait `$traitName` has $superParamsCount"
        }
    }

    class CastAsBoolError(val castExpr: RsCastExpr) : RsDiagnostic(castExpr) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0054,
            "It is not allowed to cast to a bool.",
            fixes = listOfNotNull(CompareWithZeroFix.createIfCompatible(castExpr))
        )
    }

    class ConstItemReferToStaticError(element: RsElement, val constContext: RsConstContextKind) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0013,
            when (constContext) {
                is RsConstContextKind.Constant -> "Const `${constContext.psi.name.orEmpty()}` cannot refer to static " +
                    "`${element.text}`"
                is RsConstContextKind.ConstFn -> "Constant function `${constContext.psi.name.orEmpty()}` cannot refer " +
                    "to static `${element.text}`"
                is RsConstContextKind.EnumVariantDiscriminant -> "Enum variant `${constContext.psi.name.orEmpty()}`'s " +
                    "discriminant value cannot refer to static `${element.text}`"
                RsConstContextKind.ArraySize -> "Array size cannot refer to static `${element.text}`"
                RsConstContextKind.ConstGenericArgument -> "Const generic argument cannot refer to static " +
                    "`${element.text}`"
            }
        )
    }

    class IncorrectFunctionArgumentCountError(
        element: PsiElement,
        private val expectedCount: Int,
        private val realCount: Int,
        private val functionType: FunctionType = FunctionType.FUNCTION,
        private val fixes: List<LocalQuickFix> = emptyList(),
        private val textAttributes: TextAttributesKey? = null
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            functionType.errorCode,
            errorText(),
            fixes = fixes,
            textAttributes = textAttributes
        )

        private fun errorText(): String {
            return "This function takes${if (functionType.variadic) " at least" else ""}" +
                " $expectedCount ${pluralize("parameter", expectedCount)}" +
                " but $realCount ${pluralize("parameter", realCount)}" +
                " ${if (realCount == 1) "was" else "were"} supplied"
        }

        enum class FunctionType(val variadic: Boolean, val errorCode: RsErrorCode) {
            VARIADIC_FUNCTION(true, E0060),
            FUNCTION(false, E0061),
            CLOSURE(false, E0057)
        }
    }

    class ReturnMustHaveValueError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0069,
            "`return;` in a function whose return type is not `()`"
        )
    }

    class DuplicateEnumDiscriminant(
        variant: RsEnumVariant,
        private val id: Long
    ) : RsDiagnostic(variant.variantDiscriminant?.expr ?: variant) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0081,
            "Discriminant value `$id` already exists"
        )
    }

    class ReprForEmptyEnumError(
        val attr: RsAttr,
        element: PsiElement = attr.metaItem.path?.referenceNameElement ?: attr.metaItem
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0084,
            "Enum with no variants can't have `repr` attribute",
            fixes = listOf(RemoveAttrFix(attr))
        )
    }

    class DuplicateFieldError(
        element: PsiElement,
        private val fieldName: String
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0124,
            errorText()
        )

        private fun errorText(): String {
            return "Field `$fieldName` is already declared"
        }
    }

    sealed class InvalidStartAttrError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        class ReturnMismatch(element: PsiElement) : RsDiagnostic(element) {
            override fun prepare() = PreparedAnnotation(
                ERROR,
                E0132,
                "Functions with a `start` attribute must return `isize`"
            )
        }

        class InvalidOwner(element: PsiElement) : RsDiagnostic(element) {
            override fun prepare() = PreparedAnnotation(
                ERROR,
                E0132,
                "Start attribute can be placed only on functions"
            )
        }

        class InvalidParam(
            element: PsiElement,
            private val num: Int = -1
        ) : RsDiagnostic(element) {
            override fun prepare() = PreparedAnnotation(
                ERROR,
                E0132,
                "Functions with a `start` attribute must have " + when (num) {
                    0 -> "`isize` as first parameter"
                    1 -> "`*const *const u8` as second parameter"
                    else -> "the following signature: `fn(isize, *const *const u8) -> isize`"
                }
            )
        }
    }

    class DuplicateEnumVariantError(
        element: PsiElement,
        private val fieldName: String
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0428,
            errorText()
        )

        private fun errorText(): String {
            return "Enum variant `$fieldName` is already declared"
        }
    }

    class ReservedLifetimeNameError(
        element: PsiElement,
        private val lifetimeName: String
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0262,
            errorText()
        )

        private fun errorText(): String {
            return "`$lifetimeName` is a reserved lifetime name"
        }
    }

    class DuplicateLifetimeError(
        element: PsiElement,
        private val fieldName: String
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0263,
            errorText()
        )

        private fun errorText(): String {
            return "Lifetime name `$fieldName` declared twice in the same scope"
        }
    }

    class LoopOnlyKeywordUsedInClosureError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0267,
            "`${element.text}` cannot be used in closures, only inside `loop` and `while` blocks"
        )
    }

    class LoopOnlyKeywordUsedOutsideOfLoopError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0268,
            "`${element.text}` may only be used inside `loop` and `while` blocks"
        )
    }

    class DuplicateBindingError(
        element: PsiElement,
        private val fieldName: String
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0415,
            errorText()
        )

        private fun errorText(): String {
            return "Identifier `$fieldName` is bound more than once in this parameter list"
        }
    }

    class RepeatedIdentifierInPattern(
        repeatedIdentifier: RsPatBinding,
        private val name: String
    ) : RsDiagnostic(repeatedIdentifier) {
        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                E0416,
                "Identifier `$name` is bound more than once in the same pattern"
            )
        }
    }

    class DuplicateTypeParameterError(
        element: PsiElement,
        private val fieldName: String
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0403,
            errorText()
        )

        private fun errorText(): String {
            return "The name `$fieldName` is already used for a type parameter in this type parameter list"
        }
    }

    class NotTraitError(
        element: PsiElement,
        private val found: RsItemElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0404,
            errorText()
        )

        private fun errorText(): String {
            val itemKind = found.itemKindName
            val name = found.name
            return "Expected trait, found $itemKind `$name`"
        }
    }

    class DuplicateAssociatedItemError(
        element: PsiElement,
        private val fieldName: String
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0201,
            errorText()
        )

        private fun errorText(): String {
            return "Duplicate definitions with name `$fieldName`"
        }
    }

    /**
     * [E0428] - item            vs    item
     * [E0255] - item            vs    import
     * [E0260] - item            vs    extern crate
     * [E0252] - import          vs    import
     * [E0254] - import          vs    extern crate
     * [E0259] - extern crate    vs    extern crate
     */
    class DuplicateDefinitionError private constructor(
        element: PsiElement,
        private val itemType: String,
        private val itemName: String,
        private val scopeType: String,
        private val errorCode: RsErrorCode,
    ) : RsDiagnostic(element) {

        constructor(
            element: PsiElement,
            itemNamespace: Namespace,
            itemName: String,
            scope: PsiElement,
            errorCode: RsErrorCode,
        ) : this(element, itemNamespace.itemName, itemName, scope.formatScope(), errorCode)

        override fun prepare() = PreparedAnnotation(ERROR, errorCode, errorText())

        private fun errorText(): String = when {
            element.ancestorOrSelf<RsUseSpeck>() != null ->
                "A second item with name `$itemName` imported. Try to use an alias."
            errorCode == E0259 ->
                "A second extern crate with name `$itemName` imported"
            else ->
                "A $itemType named `$itemName` has already been defined in this $scopeType"
        }

        companion object {
            private fun PsiElement.formatScope(): String =
                when (this) {
                    is RsBlock -> "block"
                    is RsMod, is RsForeignModItem -> "module"
                    is RsTraitItem -> "trait"
                    else -> "scope"
                }
        }
    }

    class ImplSizedError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0322,
            "Explicit impls for the `Sized` trait are not permitted"
        )
    }

    class ImplUnsizeError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0328,
            "Explicit impls for the `Unsize` trait are not permitted"
        )
    }

    class ConstTraitFnError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0379,
            "Trait functions cannot be declared const"
        )
    }

    class UndeclaredLabelError(
        element: RsMandatoryReferenceElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0426,
            errorText()
        )

        private fun errorText(): String {
            return "Use of undeclared label `${element.text}`"
        }
    }

    class UndeclaredLifetimeError(
        val lifetime: RsLifetime
    ) : RsDiagnostic(lifetime) {
        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                E0261,
                errorText(),
                fixes = listOfNotNull(CreateLifetimeParameterFromUsageFix.tryCreate(lifetime))
            )
        }

        private fun errorText(): String {
            return "Use of undeclared lifetime name `${element.text}`"
        }
    }

    class InBandAndExplicitLifetimesError(
        val lifetime: RsLifetime
    ) : RsDiagnostic(lifetime) {
        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                E0688,
                errorText(),
                fixes = listOfNotNull(CreateLifetimeParameterFromUsageFix.tryCreate(lifetime))
            )
        }

        private fun errorText(): String = "Cannot mix in-band and explicit lifetime definitions"
    }

    class TraitItemsMissingImplError(
        startElement: PsiElement,
        endElement: PsiElement,
        private val missing: String,
        private val impl: RsImplItem
    ) : RsDiagnostic(startElement, endElement) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0046,
            errorText(),
            fixes = listOf(ImplementMembersFix(impl))
        )

        private fun errorText(): String {
            return "Not all trait items implemented, missing: $missing"
        }
    }

    class CrateNotFoundError(
        startElement: PsiElement,
        private val crateName: String
    ) : RsDiagnostic(startElement) {
        override fun prepare() = PreparedAnnotation(
            UNKNOWN_SYMBOL,
            E0463,
            errorText()
        )

        private fun errorText(): String {
            return "Can't find crate for `$crateName`"
        }
    }

    class SizedTraitIsNotImplemented(
        element: RsTypeReference,
        private val ty: Ty
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0277,
            header = "the trait bound `$ty: std::marker::Sized` is not satisfied",
            description = "`$ty` does not have a constant size known at compile-time",
            fixes = listOf(ConvertToReferenceFix(element), ConvertToBoxFix(element))
        )
    }

    class SuperTraitIsNotImplemented(
        element: RsTraitRef,
        type: Ty,
        private val missingTrait: String
    ) : RsDiagnostic(element) {
        private val typeText = type.shortPresentableText

        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0277,
            header = "the trait bound `$typeText: $missingTrait` is not satisfied",
            description = "the trait `$missingTrait` is not implemented for `$typeText`"
        )
    }

    class ExperimentalFeature(
        element: PsiElement,
        endElement: PsiElement?,
        private val message: String,
        private val fixes: List<LocalQuickFix>
    ) : RsDiagnostic(element, endElement) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0658,
            header = message,
            fixes = fixes
        )
    }

    class UndeclaredTypeOrModule(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0433,
            header = errorText()
        )

        private fun errorText(): String {
            // TODO: support other cases
            return when (val elementType = element.elementType) {
                RsElementTypes.CRATE -> "`crate` in paths can only be used in start position"
                else -> error("Unexpected element type: `$elementType`")
            }
        }
    }

    class MissingLifetimeSpecifier(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0106,
            "Missing lifetime specifier"
        )
    }

    class WrongNumberOfLifetimeArguments(
        element: PsiElement,
        private val expectedLifetimes: Int,
        private val actualLifetimes: Int
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0107,
            errorText()
        )

        private fun errorText(): String {
            return "Wrong number of lifetime arguments: expected $expectedLifetimes, found $actualLifetimes"
        }
    }

    class WrongNumberOfGenericArguments(
        element: PsiElement,
        private val errorText: String,
        private val fixes: List<LocalQuickFix>
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                E0107,
                errorText,
                fixes = fixes
            )
        }
    }

    class WrongOrderOfGenericArguments(
        element: PsiElement,
        private val errorText: String,
        private val fixes: List<LocalQuickFix>
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0747,
            errorText,
            fixes = fixes
        )
    }

    class WrongNumberOfGenericParameters(element: PsiElement, private val errorText: String) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                E0049,
                errorText
            )
        }
    }

    class ImplForNonAdtError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0118,
            "Can impl only `struct`s, `enum`s, `union`s and trait objects"
        )
    }

    class InclusiveRangeWithNoEndError(
        element: PsiElement
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0586,
            "inclusive ranges must be bounded at the end (`..=b` or `a..=b`)"
        )
    }

    class CannotAssignToImmutable(
        element: PsiElement,
        private val message: String,
        private val fix: AddMutableFix? = null
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0594,
            "Cannot assign to $message",
            fixes = listOfNotNull(fix)
        )
    }

    class CannotReassignToImmutable(
        element: PsiElement,
        private val fix: AddMutableFix?
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0384,
            "Cannot assign twice to immutable variable",
            fixes = listOfNotNull(fix)
        )
    }

    class IncorrectVisibilityRestriction(
        private val visRestriction: RsVisRestriction
    ) : RsDiagnostic(visRestriction.path) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0704,
            "Incorrect visibility restriction",
            "Visibility restriction with module path should start with `in` keyword",
            fixes = listOf(FixVisRestriction(visRestriction))
        )
    }

    class VisibilityRestrictionMustBeAncestorModule(element: PsiElement) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0742,
            "Visibilities can only be restricted to ancestor modules",
        )
    }

    class ModuleNotFound(
        private val modDecl: RsModDeclItem
    ) : RsDiagnostic(modDecl.identifier) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            UNKNOWN_SYMBOL,
            E0583,
            "File not found for module `${modDecl.name}`",
            fixes = AddModuleFileFix.createFixes(modDecl, expandModuleFirst = false)
        )
    }

    class NonExhaustiveMatch(
        private val matchExpr: RsMatchExpr,
        private val patterns: List<Pattern>
    ) : RsDiagnostic(matchExpr.match) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0004,
            "Match must be exhaustive",
            fixes = listOfNotNull(
                AddRemainingArmsFix(matchExpr, patterns).takeIf { patterns.isNotEmpty() },
                AddWildcardArmFix(matchExpr).takeIf { matchExpr.arms.isNotEmpty() }
            )
        )
    }

    class ExpectedFunction(callExpr: RsCallExpr) : RsDiagnostic(callExpr.expr) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0618,
            "Expected function, found `${element.text}`"
        )
    }

    class UnlabeledControlFlowExpr(element: PsiElement) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                E0695,
                "Unlabeled `${element.text}` inside of a labeled block"
            )
        }
    }

    class ReprIntRequired(element: PsiElement) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0732,
            "`#[repr(inttype)]` must be specified"
        )
    }

    class NonStructuralMatchTypeAsConstGenericParameter(
        element: PsiElement,
        private val typeName: String
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0741,
            "$typeName doesn't derive both `PartialEq` and `Eq`"
        )
    }

    class ImplTraitNotAllowedHere(traitType: RsTraitType) : RsDiagnostic(traitType) {
        override fun prepare(): PreparedAnnotation =
            PreparedAnnotation(
                ERROR,
                E0562,
                "`impl Trait` not allowed outside of function and inherent method return types"
            )
    }

    class ImplTraitNotAllowedInPathParams(traitType: RsTraitType) : RsDiagnostic(traitType) {
        override fun prepare(): PreparedAnnotation =
            PreparedAnnotation(
                ERROR,
                E0667,
                "`impl Trait` is not allowed in path parameters"
            )
    }

    class NestedImplTraitNotAllowed(traitType: RsTraitType) : RsDiagnostic(traitType) {
        override fun prepare(): PreparedAnnotation =
            PreparedAnnotation(
                ERROR,
                E0666,
                "nested `impl Trait` is not allowed"
            )
    }

    class MissingFieldsInTuplePattern(
        pat: RsPat,
        private val declaration: RsFieldsOwner,
        private val expectedAmount: Int,
        private val actualAmount: Int
    ) : RsDiagnostic(pat) {
        override fun prepare(): PreparedAnnotation {
            val itemType = if (declaration is RsEnumVariant) "Enum variant" else "Tuple struct"
            return PreparedAnnotation(
                ERROR,
                E0023,
                "$itemType pattern does not correspond to its declaration: expected $expectedAmount ${pluralize("field", expectedAmount)}, found $actualAmount",
                fixes = listOf(AddStructFieldsPatFix(element), AddPatRestFix(element))
            )
        }
    }

    class MissingFieldsInStructPattern(
        pat: RsPat,
        private val declaration: RsFieldsOwner,
        private val missingFields: List<RsFieldDecl>
    ) : RsDiagnostic(pat) {
        override fun prepare(): PreparedAnnotation {
            val itemType = if (declaration is RsEnumVariant) "Enum variant" else "Struct"
            val missingFieldNames = missingFields.joinToString(", ") { "`${it.name!!}`" }
            return PreparedAnnotation(
                ERROR,
                E0027,
                "$itemType pattern does not mention ${pluralize("field", missingFields.size)} $missingFieldNames",
                fixes = listOf(AddStructFieldsPatFix(element), AddPatRestFix(element))
            )
        }
    }

    class ExtraFieldInStructPattern(private val extraField: RsPatField, private val kindName: String) : RsDiagnostic(extraField) {
        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                E0026,
                "Extra field found in the $kindName pattern: `${extraField.kind.fieldName}`"
            )
        }
    }

    class RepeatedFieldInStructPattern(
        repeatedField: RsPatField,
        private val name: String
    ) : RsDiagnostic(repeatedField) {
        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                E0025,
                "Field `$name` bound multiple times in the pattern"
            )
        }
    }

    class ExtraFieldInTupleStructPattern(
        patTupleStruct: RsPatTupleStruct,
        private val extraFieldsAmount: Int,
        private val expectedAmount: Int
    ) : RsDiagnostic(patTupleStruct) {
        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                E0023,
                "Extra fields found in the tuple struct pattern: expected $expectedAmount, found $extraFieldsAmount"
            )
        }
    }

    class MissingFieldsInUnionPattern(pat: RsPat) : RsDiagnostic(pat) {
        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                null,
                "Union patterns requires a field"
            )
        }
    }

    class TooManyFieldsInUnionPattern(pat: RsPat) : RsDiagnostic(pat) {
        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                null,
                "Union patterns should have exactly one field"
            )
        }
    }

    class MainFunctionNotFound(file: RsFile, private val crateName: String) : RsDiagnostic(file) {
        override fun prepare(): PreparedAnnotation {
            return PreparedAnnotation(
                ERROR,
                E0601,
                "`main` function not found in crate `$crateName`",
                fixes = listOf(AddMainFnFix(element))
            )
        }
    }

    class NonConstantValueInConstantError(element: PsiElement) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0435,
            "A non-constant value was used in a constant expression"
        )
    }

    class NonConstantCallInConstantError(element: PsiElement) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0015,
            "Calls in constants are limited to constant functions, tuple structs and tuple variants"
        )
    }

    class DeriveAttrUnsupportedItem(element: RsAttr) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0774,
            "`derive` may only be applied to structs, enums and unions",
            fixes = listOf(RemoveAttrFix(element as RsAttr))
        )
    }

    class NoAttrParentheses(element: RsMetaItem, private val attrName: String) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            null,
            "Malformed `$attrName` attribute input: missing parentheses",
            fixes = listOf(AddAttrParenthesesFix(element as RsMetaItem, attrName))
        )
    }

    class ReprAttrUnsupportedItem(
        element: PsiElement,
        private val errorText: String
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0517,
            errorText,
            fixes = listOf(RemoveReprValueFix(element))
        )
    }

    class UnrecognizedReprAttribute(
        element: PsiElement,
        private val reprName: String
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0552,
            "Unrecognized representation $reprName",
            fixes = listOf(RemoveReprValueFix(element))
        )
    }

    class InvalidReexport(
        element: PsiElement,
        private val name: String,
        private val exportedItem: RsItemElement
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            if (exportedItem is RsMod) E0365 else E0364,
            "`$name` is private, and cannot be re-exported",
            fixes = listOfNotNull(MakePublicFix.createIfCompatible(exportedItem, exportedItem.name, false))
        )
    }

    class DefaultsConstGenericNotAllowed(expr: RsExpr) : RsDiagnostic(expr) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            null,
            "Defaults for const parameters are only allowed in `struct`, `enum`, `type`, or `trait` definitions",
        )
    }

    class InherentImplDifferentCrateError(element: PsiElement) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0116,
            "Cannot define inherent `impl` for a type outside of the crate where the type is defined"
        )
    }

    class TraitImplOrphanRulesError(element: PsiElement) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0117,
            "Only traits defined in the current crate can be implemented for arbitrary types"
        )
    }

    class UnknownCfgPredicate(
        element: PsiElement,
        private val name: String,
        private val fixes: List<LocalQuickFix> = emptyList()
    ) : RsDiagnostic(element) {
        override fun prepare() = PreparedAnnotation(
            ERROR,
            E0537,
            "Invalid predicate `$name`",
            fixes = fixes
        )
    }

    class InvalidAbi(
        element: RsLitExpr,
        private val abiName: String
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0703,
            "Invalid ABI: found $abiName",
            description = "valid ABIs: ${SUPPORTED_CALLING_CONVENTIONS.keys.joinToString(", ")}",
            fixes = createSuggestionFixes()
        )

        private fun createSuggestionFixes(): List<NameSuggestionFix<PsiElement>> {
            val factory = RsPsiFactory(element.project)
            return NameSuggestionFix.createApplicable(element, abiName, SUPPORTED_CALLING_CONVENTIONS.keys.toList(), 2) {
                factory.createExpression("\"$it\"")
            }
        }
    }

    class FeatureAttributeInNonNightlyChannel(
        element: PsiElement,
        private val channelName: String,
        private val quickFix: RemoveElementFix?
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0554,
            "`#![feature]` may not be used on the $channelName release channel",
            fixes = listOfNotNull(quickFix)
        )
    }

    class InvalidConstGenericArgument(expr: RsExpr) : RsDiagnostic(expr) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            null,
            "Expressions must be enclosed in braces to be used as const generic arguments",
            fixes = listOf(EncloseExprInBracesFix(element as RsExpr))
        )
    }

    class IllegalLifetimeName(element: PsiElement) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            null,
            "Lifetimes cannot use keyword names"
        )
    }

    class InvalidLabelName(element: PsiElement, private val labelName: String) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            null,
            "Invalid label name `$labelName`"
        )
    }

    class SelfImportNotInUseGroup(element: PsiElement) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0429,
            "`self` imports are only allowed within a { } list",
        )
    }

    class DuplicateSelfInUseGroup(element: PsiElement) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0430,
            "The `self` import appears more than once in the list",
        )
    }

    class SelfImportInUseGroupWithEmptyPrefix(element: PsiElement) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0431,
            "`self` import can only appear in an import list with a non-empty prefix",
        )
    }

    class AwaitOutsideAsyncContext(element: PsiElement, private val fix: LocalQuickFix?) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0728,
            "`await` is only allowed inside `async` functions and blocks",
            fixes = listOfNotNull(fix)
        )
    }

    class CannotCaptureDynamicEnvironment(
        element: PsiElement,
        private val fix: LocalQuickFix?,
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0434,
            "Can't capture dynamic environment in a fn item",
            fixes = listOfNotNull(fix)
        )
    }

    class RecursiveAsyncFunction(element: PsiElement, private val fix: LocalQuickFix?) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0733,
            "Recursion in an `async fn` requires boxing",
            fixes = listOfNotNull(fix)
        )
    }

    class UnknownAssocTypeBinding(
        element: RsAssocTypeBinding,
        private val name: String,
        private val trait: String
    ) : RsDiagnostic(element) {
        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0220,
            "Associated type `$name` not found for `$trait`",
            fixes = listOf(RemoveAssocTypeBindingFix(element))
        )
    }

    class MissingAssocTypeBindings(
        element: PsiElement,
        private val missingTypes: List<RsWrongAssocTypeArgumentsInspection.MissingAssocTypeBinding>
    ) : RsDiagnostic(element) {
        private fun getText(): String {
            val typeText = pluralize("type", missingTypes.size)
            val missing = missingTypes.joinToString(", ") { "`${it.name}` (from trait `${it.trait}`)" }
            return "The value of the associated $typeText $missing must be specified"
        }

        override fun prepare(): PreparedAnnotation = PreparedAnnotation(
            ERROR,
            E0191,
            getText(),
            fixes = listOf(AddAssocTypeBindingsFix(element, missingTypes.map { it.name }))
        )
    }
}

enum class RsErrorCode {
    E0004, E0013, E0015, E0023, E0025, E0026, E0027, E0040, E0046, E0049, E0050, E0054, E0057, E0060, E0061, E0069, E0081, E0084,
    E0106, E0107, E0116, E0117, E0118, E0120, E0121, E0124, E0132, E0133, E0184, E0185, E0186, E0191, E0198, E0199,
    E0200, E0201, E0220, E0252, E0254, E0255, E0259, E0260, E0261, E0262, E0263, E0267, E0268, E0277,
    E0308, E0322, E0328, E0364, E0365, E0379, E0384,
    E0403, E0404, E0407, E0415, E0416, E0424, E0426, E0428, E0429, E0430, E0431, E0433, E0434, E0435, E0437, E0438, E0449, E0451, E0463,
    E0517, E0518, E0537, E0552, E0554, E0562, E0569, E0583, E0586, E0594,
    E0601, E0603, E0614, E0616, E0618, E0624, E0658, E0666, E0667, E0688, E0695,
    E0703, E0704, E0728, E0732, E0733, E0741, E0742, E0747, E0774;

    val code: String
        get() = toString()
    val infoUrl: String
        get() = "https://doc.rust-lang.org/error-index.html#$code"
}

enum class Severity {
    INFO, WARN, ERROR, UNKNOWN_SYMBOL
}

class PreparedAnnotation(
    val severity: Severity,
    val errorCode: RsErrorCode?,
    @Suppress("UnstableApiUsage") @InspectionMessage val header: String,
    @Suppress("UnstableApiUsage") @Tooltip val description: String = "",
    val fixes: List<LocalQuickFix> = emptyList(),
    val textAttributes: TextAttributesKey? = null
)

fun RsDiagnostic.addToHolder(holder: RsAnnotationHolder, checkExistsAfterExpansion: Boolean = true) {
    if (!checkExistsAfterExpansion || element.existsAfterExpansion) {
        addToHolder(holder.holder)
    }
}

fun RsDiagnostic.addToHolder(holder: AnnotationHolder) {
    val prepared = prepare()

    val textRange = if (endElement != null) {
        TextRange.create(
            element.startOffset,
            endElement.endOffset
        )
    } else {
        element.textRange
    }

    val message = simpleHeader(prepared.errorCode, prepared.header)

    val annotationBuilder = holder.newAnnotation(prepared.severity.toHighlightSeverity(), message)
        .tooltip(prepared.fullDescription)
        .range(textRange)
        .highlightType(prepared.severity.toProblemHighlightType())

    if (prepared.textAttributes != null) {
        annotationBuilder.textAttributes(prepared.textAttributes)
    }

    for (fix in prepared.fixes) {
        if (fix is IntentionAction) {
            annotationBuilder.withFix(fix)
        } else {
            val descriptor = InspectionManager.getInstance(element.project)
                .createProblemDescriptor(
                    element,
                    endElement ?: element,
                    message,
                    prepared.severity.toProblemHighlightType(),
                    true,
                    fix
                )

            annotationBuilder.newLocalQuickFix(fix, descriptor).registerFix()
        }
    }

    annotationBuilder.create()
}

fun RsDiagnostic.addToHolder(holder: RsProblemsHolder) {
    val prepared = prepare()
    val descriptor = holder.manager.createProblemDescriptor(
        element,
        endElement ?: element,
        prepared.fullDescription,
        prepared.severity.toProblemHighlightType(),
        holder.isOnTheFly,
        *prepared.fixes.toTypedArray()
    )
    holder.registerProblem(descriptor)
}

private val PreparedAnnotation.fullDescription: String
    get() = "<html>${htmlHeader(errorCode, escapeString(header))}<br>${escapeString(description)}</html>"

private fun Severity.toProblemHighlightType(): ProblemHighlightType = when (this) {
    INFO -> ProblemHighlightType.INFORMATION
    WARN -> ProblemHighlightType.WEAK_WARNING
    ERROR -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
    UNKNOWN_SYMBOL -> ProblemHighlightType.LIKE_UNKNOWN_SYMBOL
}

private fun Severity.toHighlightSeverity(): HighlightSeverity = when (this) {
    INFO -> HighlightSeverity.INFORMATION
    WARN -> HighlightSeverity.WARNING
    ERROR, UNKNOWN_SYMBOL -> HighlightSeverity.ERROR
}

private fun simpleHeader(error: RsErrorCode?, description: String): String =
    if (error == null) {
        description
    } else {
        "$description [${error.code}]"
    }

private fun htmlHeader(error: RsErrorCode?, description: String): String =
    if (error == null) {
        description
    } else {
        "$description [<a href='${error.infoUrl}'>${error.code}</a>]"
    }

private val RsSelfParameter.canonicalDecl: String
    get() = buildString {
        if (isRef) append('&')
        if (mutability.isMut) append("mut ")
        append("self")
    }

private fun Ty.rendered(useQualifiedName: Set<RsQualifiedNamedElement> = emptySet()): String =
    render(useQualifiedName = useQualifiedName)

private fun getConflictingNames(element: PsiElement, vararg tys: Ty): Set<RsQualifiedNamedElement> {
    val context = element.ancestorOrSelf<RsElement>()
    return if (context != null) {
        getTypeReferencesInfoFromTys(context, *tys).toQualify
    } else {
        emptySet()
    }
}

val SUPPORTED_CALLING_CONVENTIONS = mapOf(
    "Rust" to null,
    "C" to null,
    "C-unwind" to C_UNWIND,
    "cdecl" to null,
    "stdcall" to null,
    "stdcall-unwind" to C_UNWIND,
    "fastcall" to null,
    "vectorcall" to ABI_VECTORCALL,
    "thiscall" to ABI_THISCALL,
    "thiscall-unwind" to C_UNWIND,
    "aapcs" to null,
    "win64" to null,
    "sysv64" to null,
    "ptx-kernel" to ABI_PTX,
    "msp430-interrupt" to ABI_MSP430_INTERRUPT,
    "x86-interrupt" to ABI_X86_INTERRUPT,
    "amdgpu-kernel" to ABI_AMDGPU_KERNEL,
    "efiapi" to ABI_EFIAPI,
    "avr-interrupt" to ABI_AVR_INTERRUPT,
    "avr-non-blocking-interrupt" to ABI_AVR_INTERRUPT,
    "C-cmse-nonsecure-call" to ABI_C_CMSE_NONSECURE_CALL,
    "wasm" to WASM_ABI,
    "system" to null,
    "system-unwind" to C_UNWIND,
    "rust-intrinsic" to INTRINSICS,
    "rust-call" to UNBOXED_CLOSURES,
    "platform-intrinsic" to PLATFORM_INTRINSICS,
    "unadjusted" to ABI_UNADJUSTED
)

fun RsElement.areUnstableFeaturesAvailable(version: RustcVersion): ThreeState {
    val crate = containingCrate

    val origin = crate.origin
    val isStdlibPart = origin == PackageOrigin.STDLIB || origin == PackageOrigin.STDLIB_DEPENDENCY
    return if (version.channel != RustChannel.NIGHTLY && !isStdlibPart) ThreeState.NO else ThreeState.YES
}
