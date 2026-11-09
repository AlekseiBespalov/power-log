package app.powerlog.data

import app.powerlog.application.store.ArchiveHarness
import app.powerlog.application.store.BatchAtomicityContract
import app.powerlog.application.store.CacheContract
import app.powerlog.application.store.ClosedRideContract
import app.powerlog.application.store.ColumnRangeContract
import app.powerlog.application.store.DeletionContract
import app.powerlog.application.store.DurabilityContract
import app.powerlog.application.store.EndCheckpointContract
import app.powerlog.application.store.ImpliedExistenceContract
import app.powerlog.application.store.KeysetPagingContract
import app.powerlog.application.store.LastFactContract
import app.powerlog.application.store.MeasurementReadsContract
import app.powerlog.application.store.NearestContract
import app.powerlog.application.store.OtherTabContract
import app.powerlog.application.store.RefusedArchiveContract
import app.powerlog.application.store.RememberedBikeContract
import app.powerlog.application.store.RevisionContract
import app.powerlog.application.store.RidePagesContract
import app.powerlog.application.store.SettingsContract
import app.powerlog.application.store.VocabularyContract
import app.powerlog.application.store.WebArchiveHarness
import app.powerlog.application.store.WriterQueueContract

private val idbHarness = IdbArchiveHarness()

private fun batchAtomicity() =
    object : BatchAtomicityContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun impliedExistence() =
    object : ImpliedExistenceContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun closedRide() =
    object : ClosedRideContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun revision() =
    object : RevisionContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun writerQueue() =
    object : WriterQueueContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun durability() =
    object : DurabilityContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun endCheckpoint() =
    object : EndCheckpointContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun columnRange() =
    object : ColumnRangeContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun keysetPaging() =
    object : KeysetPagingContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun ridePages() =
    object : RidePagesContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun measurementReads() =
    object : MeasurementReadsContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun nearest() =
    object : NearestContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun lastFact() =
    object : LastFactContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun vocabulary() =
    object : VocabularyContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun rememberedBike() =
    object : RememberedBikeContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun deletion() =
    object : DeletionContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun settings() =
    object : SettingsContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun cache() =
    object : CacheContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun refusedArchive() =
    object : RefusedArchiveContract() {
        override val harness: ArchiveHarness = idbHarness
    }

private fun otherTab() =
    object : OtherTabContract() {
        override val harness: WebArchiveHarness = idbHarness
    }

internal val idbCases: List<Pair<String, BrowserCase>> =
    listOf(
        browserCase("IdbBatchAtomicityTest.aBatchWhoseLastMutationFailsLeavesNoRowRideChangeRevisionOrNotice") {
            batchAtomicity().aBatchWhoseLastMutationFailsLeavesNoRowRideChangeRevisionOrNotice()
        },
        browserCase("IdbImpliedExistenceTest.aMutationNamingAnAbsentRideAnswersRideGoneAndAppliesNothing") {
            impliedExistence().aMutationNamingAnAbsentRideAnswersRideGoneAndAppliesNothing()
        },
        browserCase("IdbImpliedExistenceTest.aMutationNamingADeletedRideAnswersRideGoneAndAppliesNothing") {
            impliedExistence().aMutationNamingADeletedRideAnswersRideGoneAndAppliesNothing()
        },
        browserCase("IdbImpliedExistenceTest.aRideCreatedEarlierInTheBatchTakesTheMutationsAfterIt") {
            impliedExistence().aRideCreatedEarlierInTheBatchTakesTheMutationsAfterIt()
        },
        browserCase("IdbClosedRideTest.anAbsentOrDeletedClosedRideAnswersRideGone") {
            closedRide().anAbsentOrDeletedClosedRideAnswersRideGone()
        },
        browserCase("IdbClosedRideTest.aClosedRideInAnOpenPhaseAnswersRideOpenAndStays") {
            closedRide().aClosedRideInAnOpenPhaseAnswersRideOpenAndStays()
        },
        browserCase("IdbClosedRideTest.aCompletedOrFailedClosedRideTakesTheBatch") {
            closedRide().aCompletedOrFailedClosedRideTakesTheBatch()
        },
        browserCase("IdbRevisionTest.eachCommitMovesTheRevisionsItsMutationsChangeOnceAndNoticesCarryThem") {
            revision().eachCommitMovesTheRevisionsItsMutationsChangeOnceAndNoticesCarryThem()
        },
        browserCase("IdbWriterQueueTest.aFullQueueAnswersBusyAtOnceAndQueuedBatchesCommitInOrder") {
            writerQueue().aFullQueueAnswersBusyAtOnceAndQueuedBatchesCommitInOrder()
        },
        browserCase("IdbDurabilityTest.aCommittedStateReadsBackAfterTheStoreIsClosedAndOpened") {
            durability().aCommittedStateReadsBackAfterTheStoreIsClosedAndOpened()
        },
        browserCase("IdbEndCheckpointTest.aFinishBatchSetsTheCheckpointToTheEndBelowAStoredLaterOne") {
            endCheckpoint().aFinishBatchSetsTheCheckpointToTheEndBelowAStoredLaterOne()
        },
        browserCase("IdbColumnRangeTest.everyRideColumnRoundTripsAtItsExtremesAndInEveryVocabulary") {
            columnRange().everyRideColumnRoundTripsAtItsExtremesAndInEveryVocabulary()
        },
        browserCase("IdbColumnRangeTest.everyEventColumnRoundTrips") { columnRange().everyEventColumnRoundTrips() },
        browserCase("IdbColumnRangeTest.everyControllerSessionColumnRoundTrips") {
            columnRange().everyControllerSessionColumnRoundTrips()
        },
        browserCase("IdbColumnRangeTest.everyTelemetryColumnRoundTripsAtItsMinimumMaximumZeroAndAbsence") {
            columnRange().everyTelemetryColumnRoundTripsAtItsMinimumMaximumZeroAndAbsence()
        },
        browserCase("IdbColumnRangeTest.everyLocationColumnRoundTripsAtItsExtremesAndAbsence") {
            columnRange().everyLocationColumnRoundTripsAtItsExtremesAndAbsence()
        },
        browserCase("IdbColumnRangeTest.knownControllersKeepTheirBikeIdAsGivenAndTheirTexts") {
            columnRange().knownControllersKeepTheirBikeIdAsGivenAndTheirTexts()
        },
        browserCase("IdbKeysetPagingTest.telemetryPagesHoldEveryRowOnceInKeyOrderWithNextNullExactlyAtTheEnd") {
            keysetPaging().telemetryPagesHoldEveryRowOnceInKeyOrderWithNextNullExactlyAtTheEnd()
        },
        browserCase("IdbKeysetPagingTest.locationPagesHoldEveryFixOnceInKeyOrderWithNextNullExactlyAtTheEnd") {
            keysetPaging().locationPagesHoldEveryFixOnceInKeyOrderWithNextNullExactlyAtTheEnd()
        },
        browserCase("IdbRidePagesTest.pagesAreNewestFirstWithDeletedRidesHiddenAndLimitCountingRides") {
            ridePages().pagesAreNewestFirstWithDeletedRidesHiddenAndLimitCountingRides()
        },
        browserCase("IdbRidePagesTest.aRideTheWholePagingSeesAppearsOnceWhileOthersComeAndGo") {
            ridePages().aRideTheWholePagingSeesAppearsOnceWhileOthersComeAndGo()
        },
        browserCase("IdbRidePagesTest.rideAndOpenRideAnswerTheUndeletedRideAndMarkerTheHistoryRevision") {
            ridePages().rideAndOpenRideAnswerTheUndeletedRideAndMarkerTheHistoryRevision()
        },
        browserCase("IdbMeasurementReadsTest.extentAnswersEachKindsRangeAndTheHealthMetricsAndProducers") {
            measurementReads().extentAnswersEachKindsRangeAndTheHealthMetricsAndProducers()
        },
        browserCase("IdbMeasurementReadsTest.sessionsCarryTheirDecodersCapabilitiesInSessionOrder") {
            measurementReads().sessionsCarryTheirDecodersCapabilitiesInSessionOrder()
        },
        browserCase("IdbMeasurementReadsTest.aSessionOfAnUnknownDecoderFailsItsReadAsConstraint") {
            measurementReads().aSessionOfAnUnknownDecoderFailsItsReadAsConstraint()
        },
        browserCase("IdbMeasurementReadsTest.eventsAnswerInSeqOrderWhichIsElapsedOrder") {
            measurementReads().eventsAnswerInSeqOrderWhichIsElapsedOrder()
        },
        browserCase(
            "IdbMeasurementReadsTest.telemetryCarriesSpeedRawAndRaceModeFromTheirColumnsAndNeverControllerSpeed"
        ) {
            measurementReads().telemetryCarriesSpeedRawAndRaceModeFromTheirColumnsAndNeverControllerSpeed()
        },
        browserCase("IdbNearestTest.nearestAnswersTheClosestTelemetryRowReportingTheMetricWithinTheBound") {
            nearest().nearestAnswersTheClosestTelemetryRowReportingTheMetricWithinTheBound()
        },
        browserCase("IdbNearestTest.nearestAnswersTheClosestFixWhoseColumnForTheMetricHoldsAValue") {
            nearest().nearestAnswersTheClosestFixWhoseColumnForTheMetricHoldsAValue()
        },
        browserCase("IdbLastFactTest.lastFactAnswersTheLatestTelemetryRowOrFixAndIgnoresEvents") {
            lastFact().lastFactAnswersTheLatestTelemetryRowOrFixAndIgnoresEvents()
        },
        browserCase("IdbVocabularyTest.aStoredValueOutsideItsVocabularyFailsOnlyTheReadsOfItsRow") {
            vocabulary().aStoredValueOutsideItsVocabularyFailsOnlyTheReadsOfItsRow()
        },
        browserCase("IdbRememberedBikeTest.aRememberedBikeAndTheSelectionReadBackAndAForgottenBikeAnswersNull") {
            rememberedBike().aRememberedBikeAndTheSelectionReadBackAndAForgottenBikeAnswersNull()
        },
        browserCase("IdbDeletionTest.aDeletedRideIsHiddenTakesNoWritesAndItsCleanupResumesAcrossAReopen") {
            deletion().aDeletedRideIsHiddenTakesNoWritesAndItsCleanupResumesAcrossAReopen()
        },
        browserCase("IdbDeletionTest.cleanupSplitsKeyTiesAndDeletesTheHealthWorkADeletionLeftBehind") {
            deletion().cleanupSplitsKeyTiesAndDeletesTheHealthWorkADeletionLeftBehind()
        },
        browserCase("IdbSettingsTest.noSettingsRowIsMissingAndASavedDocumentReadsBackAsItsDraft") {
            settings().noSettingsRowIsMissingAndASavedDocumentReadsBackAsItsDraft()
        },
        browserCase("IdbSettingsTest.textThatIsNotAJsonObjectIsUnreadable") {
            settings().textThatIsNotAJsonObjectIsUnreadable()
        },
        browserCase("IdbSettingsTest.unknownKeysAndWrongTypesDropAndEveryKnownFieldOfTheRightTypeStays") {
            settings().unknownKeysAndWrongTypesDropAndEveryKnownFieldOfTheRightTypeStays()
        },
        browserCase("IdbSettingsTest.resetSavesTheDefaultsOverAnUnreadableRow") {
            settings().resetSavesTheDefaultsOverAnUnreadableRow()
        },
        browserCase("IdbCacheTest.aSummaryIsAHitOnlyAtTheRevisionItWasPutWithAndTheNextPutOverwritesIt") {
            cache().aSummaryIsAHitOnlyAtTheRevisionItWasPutWithAndTheNextPutOverwritesIt()
        },
        browserCase("IdbCacheTest.tilesAreHitsAtTheirRevisionAndFinalTilesAtAnyRevision") {
            cache().tilesAreHitsAtTheirRevisionAndFinalTilesAtAnyRevision()
        },
        browserCase("IdbCacheTest.aChangedRulesVersionOrLayoutEmptiesTheCache") {
            cache().aChangedRulesVersionOrLayoutEmptiesTheCache()
        },
        browserCase("IdbCacheTest.anUndecodableValueIsAMissAndAFailingWriteThrowsNothing") {
            cache().anUndecodableValueIsAMissAndAFailingWriteThrowsNothing()
        },
        browserCase("IdbCacheTest.dropRideRemovesOnlyThatRidesEntriesEvenWithNoRideRow") {
            cache().dropRideRemovesOnlyThatRidesEntriesEvenWithNoRideRow()
        },
        browserCase("IdbRefusedArchiveTest.anArchiveOfAnotherShapeIsRefusedAsUnreadableAndLeftByteForByte") {
            refusedArchive().anArchiveOfAnotherShapeIsRefusedAsUnreadableAndLeftByteForByte()
        },
        browserCase("IdbRefusedArchiveTest.callsBeforeOpenAnswerClosed") {
            refusedArchive().callsBeforeOpenAnswerClosed()
        },
        browserCase("IdbOtherTabTest.aCommitInOneConnectionArrivesInTheOtherAsAnOtherTabNotice") {
            otherTab().aCommitInOneConnectionArrivesInTheOtherAsAnOtherTabNotice()
        },
        browserCase("IdbOtherTabTest.aDeleteOfARideTheOtherConnectionHasOpenAnswersRideOpen") {
            otherTab().aDeleteOfARideTheOtherConnectionHasOpenAnswersRideOpen()
        },
    )
