# 当前自动测试源索引

编制日期：2026-10-08。状态：`SOURCE_INVENTORY_ONLY_NOT_EXECUTION`。

仅扫描当前源码，不判定本轮是否执行、跳过或通过；实际结果须另查指定构建/设备的完整执行报告。

JVM `@Test fun` 方法 **392** 个；Android **260** 个。每个具体方法声明只计一次，不是手册用例数。

重建：`python scripts/index_tests.py`；只读检查一致性：`python scripts/index_tests.py --check`；固定日期可加 `--date YYYY-MM-DD`。脚本只读源码并生成这两份索引，不执行测试、ADB或构建。

参数化、动态生成、循环内数据集及重复运行不展开计数。`assume`、显式opt-in、设备/网络/SAF条件及`@Ignore`可能使断言未执行，不能把JUnit汇总数字或本索引条目直接记为PASS。下列assume位置是类级提示，不等于类内每个方法均有条件。

范围：`app/src/test/**/*.kt`与`app/src/androidTest/**/*.kt`的具体`@Test fun`；排除注释/字符串、Java/JUnit3/非@Test、parser-lab JavaScript与生成源码。

## JVM

### AlbumBgmPolicyTest (7)

源码：[app/src/test/java/com/local/douyinsaver/AlbumBgmPolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/AlbumBgmPolicyTest.kt)

- `officialMusicUrlsArePreservedAndTrustedHttpIsUpgradedToTlsOnly`（源码行 13；仅源码登记）
- `opaqueMusicIdsAndUntrustedOrMalformedAddressesCannotCreateAudioUrls`（源码行 20；仅源码登记）
- `unsupportedFirstCandidateDoesNotHideItsMatchingWorkAudio`（源码行 29；仅源码登记）
- `audioBearingWorkMp4CanBeOfferedAsBgmAndDoesNotReplaceOrderedImages`（源码行 33；仅源码登记）
- `missingBgmHasBoundedGraceForLateHydrationWithoutBlockingImageOnlyDownload`（源码行 42；仅源码登记）
- `musicReadinessDoesNotDelayOrdinaryVideoOrAnAlbumWithItsOwnAudio`（源码行 50；仅源码登记）
- `matchingAudioCannotMakeADifferentOwnerAlbumValid`（源码行 56；仅源码登记）

### AlbumDynamicPolicyTest (9)

源码：[app/src/test/java/com/local/douyinsaver/AlbumDynamicPolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/AlbumDynamicPolicyTest.kt)

- `staticAnimatedAndUnclassifiedMotionKeepTheirOriginalOrderAndSoundtrack`（源码行 21；仅源码登记）
- `exactPhotoVideoIdCanSupplyItsOwnCleanPlaybackEntry`（源码行 33；仅源码登记）
- `missingClipKeepsItsUnknownDynamicKindAndExplicitLiveRemainsLive`（源码行 41；仅源码登记）
- `untrustedClipOriginsCannotProvideMotionOrDiscardAnotherPhoto`（源码行 50；仅源码登记）
- `aWatermarkedClipCannotGainCleanProvenanceFromItsCleanStaticCover`（源码行 59；仅源码登记）
- `unsafeDurationDimensionsAndMimeHintsAreBoundedWithoutChangingSignedAddresses`（源码行 66；仅源码登记）
- `aDifferentWorkCannotGrantItsDynamicClipToTheTargetAlbum`（源码行 77；仅源码登记）
- `aGifMimeHintDoesNotRequireMultipleFramesBeforeTheFileHasBeenRead`（源码行 82；仅源码登记）
- `explicitAnimationLiveAndUnknownKindsSurviveIdenticalOwnedMp4Sources`（源码行 89；仅源码登记）

### AlbumKindPreservationTest (7)

源码：[app/src/test/java/com/local/douyinsaver/AlbumKindPreservationTest.kt](../../app/src/test/java/com/local/douyinsaver/AlbumKindPreservationTest.kt)

- `reorderedUniqueIdentitiesRetainKnownKindsWithoutReplacingDesktopMedia`（源码行 7；仅源码登记）
- `duplicateKeysInEitherArrayRemainUnknownEvenWithMatchingUrls`（源码行 22；仅源码登记）
- `blankKeysNeverFallBackToSameUrlOrPosition`（源码行 33；仅源码登记）
- `aDifferentWorkCannotCarryOverItsClassification`（源码行 42；仅源码登记）
- `conflictingKeysNeverMatchByTheSameExactUrlOrSequencePosition`（源码行 48；仅源码登记）
- `explicitCandidateKindsRemainIntactIncludingOppositeKnownKindsAndStatic`（源码行 55；仅源码登记）
- `missingMotionAndPreviouslyUnknownTypesCannotBePromoted`（源码行 65；仅源码登记）

### AlbumMediaPolicyTest (5)

源码：[app/src/test/java/com/local/douyinsaver/AlbumMediaPolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/AlbumMediaPolicyTest.kt)

- `derivesDurationWithoutIntegerOverflowAndRejectsExcessiveWork`（源码行 10；仅源码登记）
- `decodedMimeDeterminesExtensionAndGuardsPixelCount`（源码行 18；仅源码登记）
- `choosesConsistentEvenCanvasAndChecksTrueByteLimits`（源码行 28；仅源码登记）
- `usesTrackAndSizeToDistinguishAudioPrimingFromEndOfStream`（源码行 38；仅源码登记）
- `rejectsSilentStillCompositionAndBoundsLiveSidecars`（源码行 46；仅源码登记）

### AlbumPageFallbackTest (8)

源码：[app/src/test/java/com/local/douyinsaver/AlbumPageFallbackTest.kt](../../app/src/test/java/com/local/douyinsaver/AlbumPageFallbackTest.kt)

- `theOfficialMobileNoteAndItsSignedQueryAreUsedUnchanged`（源码行 10；仅源码登记）
- `officialMobileVideoAndSlidesRoutesKeepTheirProvidedContext`（源码行 17；仅源码登记）
- `desktopOrFeaturedRoutesUseTheDefaultMobileVideoPage`（源码行 25；仅源码登记）
- `theInitialMobilePageMustMatchTheWorkAndRetainTheOriginRules`（源码行 32；仅源码登记）
- `theRealSamplesConfirmedEmptyVideoRouteMayTryItsOfficialNoteRouteOnce`（源码行 42；仅源码登记）
- `incompleteLoadingRouteMismatchAndVisibleVerificationDoNotTriggerTheFallback`（源码行 47；仅源码登记）
- `aNoteErrorOrAnotherWorksErrorCannotRepeatOrRedirectTheFallback`（源码行 53；仅源码登记）
- `onlyTheTrustedHttpsVideoRouteCanEnableTheFixedOfficialRequest`（源码行 59；仅源码登记）

### AlbumPresentationTest (16)

源码：[app/src/test/java/com/local/douyinsaver/AlbumPresentationTest.kt](../../app/src/test/java/com/local/douyinsaver/AlbumPresentationTest.kt)

- `mixedGalleryCountsLivePairsAsOneItem`（源码行 7；仅源码登记）
- `earlierStaticGalleryKeepsItsImageOrder`（源码行 25；仅源码登记）
- `composedGalleryRemainsVideoRatherThanFakeImageList`（源码行 33；仅源码登记）
- `missingLiveMotionStaysVisibleAsLiveCover`（源码行 40；仅源码登记）
- `untypedLegacyMotionManifestDoesNotGuessLiveType`（源码行 49；仅源码登记）
- `partialDeletionKeepsOrphanMotionPlayableWithoutDecodingItAsImage`（源码行 56；仅源码登记）
- `animatedVideoRemainsAnimatedRatherThanBeingRelabelledLive`（源码行 70；仅源码登记）
- `embeddedLivePhotoHasOneSavedUriAndExplicitExtractableMotion`（源码行 84；仅源码登记）
- `staticSequenceNeedsTwoPicturesAndDynamicActionsAdaptToSourceKinds`（源码行 95；仅源码登记）
- `automaticTimingUsesEachActualMotionAndDoesNotInventMissingDurations`（源码行 110；仅源码登记）
- `customTimingAcceptsTenthsAndConfirmationExplainsTrimOrLoop`（源码行 121；仅源码登记）
- `confirmationKeepsMillisecondDifferencesVisibleAtTenthsBoundaries`（源码行 134；仅源码登记）
- `untypedMotionRequiresExplicitOutputChoiceInsteadOfGuessingLiveOrAnimation`（源码行 143；仅源码登记）
- `knownLiveAndNativeAnimationKeepTheirDefaultWithoutUnknownChoices`（源码行 159；仅源码登记）
- `generatingALiveCoverIsASeparateExplicitActionForOwnedUntypedMotionOnly`（源码行 173；仅源码登记）
- `missingUntypedMotionStaticAndKnownLiveKeepTheirOriginalActions`（源码行 186；仅源码登记）

### AlbumRecordPolicyTest (7)

源码：[app/src/test/java/com/local/douyinsaver/AlbumRecordPolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/AlbumRecordPolicyTest.kt)

- `gifQualityChoicesDoNotSuppressEachOthersDownloadsOrInvalidateOldClearExports`（源码行 16；仅源码登记）
- `explicitAnimationConversionMatchesOnlyARealEmbeddedLiveManifest`（源码行 27；仅源码登记）
- `staticCoversCannotSuppressNewLiveMotionAndIncompletePairsDoNotMatch`（源码行 37；仅源码登记）
- `mixedGalleryCountsAssetsRatherThanPublishedFiles`（源码行 49；仅源码登记）
- `legacyImagesNeedFreshManifestsAndComposedVideosRemainSeparate`（源码行 62；仅源码登记）
- `partialDeletionPreservesActualTypesAndNeverLeavesDanglingPairLinks`（源码行 79；仅源码登记）
- `unknownDynamicHistoryMatchesOnlyItsExplicitSavedFormat`（源码行 97；仅源码登记）

### AlbumSourcePolicyTest (15)

源码：[app/src/test/java/com/local/douyinsaver/AlbumSourcePolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/AlbumSourcePolicyTest.kt)

- `legacySingleUrlDoesNotBecomeAnInventedCleanSource`（源码行 20；仅源码登记）
- `theOwnedDisplayFieldDoesNotRequireAKnownWatermarkedDownloadDerivative`（源码行 31；仅源码登记）
- `aSingleDisplayOnlyPhotoRetainsItsExactSignedAddressAndMusic`（源码行 41；仅源码登记）
- `aSharedDisplayAndDownloadAddressIsCleanWithoutAConflictingMarkedLabel`（源码行 53；仅源码登记）
- `anUnknownDownloadOnlyPhotoStillCannotReplaceTheDisplayedRendition`（源码行 65；仅源码登记）
- `explicitWatermarksAndAmbiguousSwitchesCannotBePromotedByDisplayMetadata`（源码行 71；仅源码登记）
- `aWatermarkWordInAnAuthorFilenameIsNotAPlatformTransform`（源码行 82；仅源码登记）
- `allDisplayOnlyImagesKeepTheirOrderWithoutFlattenedUrlsOrBackgroundMusic`（源码行 88；仅源码登记）
- `aDifferentWorkCannotGrantDisplayProvenanceToAnAlbum`（源码行 98；仅源码登记）
- `knownDownloadWatermarkRetainsTheCorrespondingDisplayAlternative`（源码行 103；仅源码登记）
- `selectedAlbumKeepsImageOrderMusicAndOriginalCandidates`（源码行 112；仅源码登记）
- `missingOneCleanImageRejectsTheEntireAlbumBeforeTransfer`（源码行 124；仅源码登记）
- `separatedCandidatesCanProvideTheLegacyPreviewWhenFlattenedUrlsAreAbsent`（源码行 134；仅源码登记）
- `unsupportedVariantsDoNotMakeAnIncompleteAlbumSuccessful`（源码行 140；仅源码登记）
- `legacyAlbumRetainsAllOriginalAlternativesAndDoesNotClaimAnyWatermarkMode`（源码行 147；仅源码登记）

### AlbumTimingTest (18)

源码：[app/src/test/java/com/local/douyinsaver/AlbumTimingTest.kt](../../app/src/test/java/com/local/douyinsaver/AlbumTimingTest.kt)

- `persistedDecimalStaticDefaultFeedsGifAndMixedCompositionWithoutChangingMotion`（源码行 7；仅源码登记）
- `decimalDefaultsPreserveLegacyRecordSignaturesAndSeparateChangedDefaults`（源码行 19；仅源码登记）
- `decimalDefaultSummaryKeepsEachMotionAndAddsOnlyStaticDefaults`（源码行 28；仅源码登记）
- `singleItemConfirmationKeepsItsOriginalGalleryPosition`（源码行 38；仅源码登记）
- `decimalInputUsesTenthsOfASecondIncludingPointOne`（源码行 45；仅源码登记）
- `rejectsEmptyNonfiniteAndOutsideSupportedInputRange`（源码行 51；仅源码登记）
- `automaticMixedGalleryKeepsEachExactMotionDurationAndStaticDefault`（源码行 56；仅源码登记）
- `customTenthsOverrideAutomaticSuggestionForAllSelectedItems`（源码行 61；仅源码登记）
- `missingMotionDurationUsesStaticDefaultWithoutInventingDynamicLength`（源码行 65；仅源码登记）
- `mismatchReportIncludesOnlyActualMotionAdjustmentsAndKeepsIndices`（源码行 68；仅源码登记）
- `trimUsesOnlyRequestedLeadingSegmentAndLongerDurationRepeatsWholeSourceThenTail`（源码行 72；仅源码登记）
- `invalidOrExcessiveLoopRequestsFailBeforeExport`（源码行 78；仅源码登记）
- `wholeCompositionHasAnExplicitTotalLimit`（源码行 83；仅源码登记）
- `selectionRetainsOriginalOrderAndOneItemDoesNotIncludeNeighbors`（源码行 88；仅源码登记）
- `staleDuplicateOrOutOfBoundsSelectionsFailWithoutSilentlySavingWholeAlbum`（源码行 95；仅源码登记）
- `enrichedSelectionFollowsExactImageIdentityAcrossReorderedSignedSources`（源码行 102；仅源码登记）
- `twoSelectedOriginalsCannotSilentlyCollapseIntoOneCandidateSlot`（源码行 115；仅源码登记）
- `multipleCandidateMatchesFailWithoutGuessingByArrayPosition`（源码行 127；仅源码登记）

### AnimatedImageVideoPolicyTest (5)

源码：[app/src/test/java/com/local/douyinsaver/AnimatedImageVideoPolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/AnimatedImageVideoPolicyTest.kt)

- `retainsHdCompositionInputWithoutTheCompatibilityGifDownscale`（源码行 8；仅源码登记）
- `boundsLargerSourcesWhileKeepingTheirAspectRatio`（源码行 15；仅源码登记）
- `sourceGeometryIsRetainedWhenAnEncoderAcceptsSmallFrames`（源码行 24；仅源码登记）
- `encoderMinimumAndAlignmentAddPaddingWithoutChangingSourceGeometry`（源码行 29；仅源码登记）
- `unsupportedOrUnboundedEncoderConstraintsAreRejectedBeforeAllocatingPixels`（源码行 37；仅源码登记）

### AppearanceOptionsTest (9)

源码：[app/src/test/java/com/local/douyinsaver/AppearanceOptionsTest.kt](../../app/src/test/java/com/local/douyinsaver/AppearanceOptionsTest.kt)

- `acceptsOnlyOpaqueSixDigitColors`（源码行 7；仅源码登记）
- `clampsInvalidPersistedSliderValues`（源码行 12；仅源码登记）
- `invalidCustomColorFallsBackWithoutChangingPreset`（源码行 17；仅源码登记）
- `defaultsUseThinPanelsAndIndependentTextProtection`（源码行 21；仅源码登记）
- `acceptsEntireOpacityRangeWithoutHiddenMinimum`（源码行 31；仅源码登记）
- `protectionCanBeDisabledWithoutChangingPanelOpacity`（源码行 36；仅源码登记）
- `legacyDefaultMigrationKeepsImageCropAndTheme`（源码行 42；仅源码登记）
- `migrationPreservesDeliberatePanelAndDarknessAdjustments`（源码行 54；仅源码登记）
- `softPresetKeepsChosenImageAndPosition`（源码行 62；仅源码登记）

### DesktopAlbumPagePolicyTest (8)

源码：[app/src/test/java/com/local/douyinsaver/DesktopAlbumPagePolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/DesktopAlbumPagePolicyTest.kt)

- `desktopAgentKeepsTheInstalledChromiumVersion`（源码行 13；仅源码登记）
- `anUnknownBrowserVersionIsNotReplacedWithAnInventedVersion`（源码行 25；仅源码登记）
- `automaticNavigationStaysWithTheRequestedOfficialWork`（源码行 30；仅源码登记）
- `loginNavigationIsAllowedOnlyDuringExplicitManualVerification`（源码行 45；仅源码登记）
- `manualVerificationCannotNavigateToAnotherWorkOrAnUnrelatedPage`（源码行 61；仅源码登记）
- `protocolsCredentialsAndNonstandardPortsStayBlockedInEveryPhase`（源码行 79；仅源码登记）
- `refreshedSignaturesDoNotResetTheSameImageMotionIdentity`（源码行 97；仅源码登记）
- `genuineMediaIdsAndFilePathsRemainPartOfStability`（源码行 109；仅源码登记）

### DesktopMotionDisplaySourcesTest (8)

源码：[app/src/test/java/com/local/douyinsaver/DesktopMotionDisplaySourcesTest.kt](../../app/src/test/java/com/local/douyinsaver/DesktopMotionDisplaySourcesTest.kt)

- `aLonePlaybackUrlWithoutTheDisplayRoleRemainsUnclassified`（源码行 13；仅源码登记）
- `ownedDisplayPlaybackRolesRetainEverySignedAddressExactly`（源码行 20；仅源码登记）
- `aRoleOutsideTheExactPlaybackFieldDoesNotAddOrClassifyAnAddress`（源码行 29；仅源码登记）
- `displayRolesDoNotOverrideMarkedOrAmbiguousAddresses`（源码行 40；仅源码登记）
- `unsafeDisplayPlaybackRolesCannotBecomeMediaSources`（源码行 57；仅源码登记）
- `anIdenticalDownloadRequestDoesNotConflictWithAConfirmedDisplayRole`（源码行 65；仅源码登记）
- `ordinaryVideoSourceSelectionKeepsItsExistingDefaultRules`（源码行 79；仅源码登记）
- `albumMappingUsesOnlyTheConfirmedOwnedMotionRole`（源码行 89；仅源码登记）

### DesktopVideoCandidatePolicyTest (5)

源码：[app/src/test/java/com/local/douyinsaver/DesktopVideoCandidatePolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/DesktopVideoCandidatePolicyTest.kt)

- `exactOwnedDesktopPlaybackRoleRetainsSignedAddressAndStillRequiresNetworkVerification`（源码行 15；仅源码登记）
- `recommendationsWrongRoutesAndUntrustedOriginsCannotSupplyThisWork`（源码行 24；仅源码登记）
- `aLoneSnakeSourceOrMarkedUrlCannotInheritAnUnrelatedDesktopDisplayRole`（源码行 32；仅源码登记）
- `onlyTheOwnedWorkCanSupplyAlternateDimensionsOrMediaIdentifiers`（源码行 40；仅源码登记）
- `incompleteInvalidOrNonfiniteMetadataDoesNotBecomeReady`（源码行 48；仅源码登记）

### DiagnosticTextTest (14)

源码：[app/src/test/java/com/local/douyinsaver/DiagnosticTextTest.kt](../../app/src/test/java/com/local/douyinsaver/DiagnosticTextTest.kt)

- `rejectedRedirectHostSurvivesRepeatedDiagnosticsCleaning`（源码行 9；仅源码登记）
- `longHostFieldsAndQuotedJsonHostsKeepTheirCompleteDnsNames`（源码行 22；仅源码登记）
- `longUrlOriginsStillHideAllCredentialsAndSignedComponents`（源码行 27；仅源码登记）
- `preservingAHostDoesNotPreserveSecretsOnTheSameLineOrFoldedHeaders`（源码行 32；仅源码登记）
- `onlyCompleteValidDnsHostFieldsAreExemptFromOpaqueRedaction`（源码行 40；仅源码登记）
- `longHostRetentionStillHonorsTheMessageLimit`（源码行 55；仅源码登记）
- `keepsUsefulJavascriptErrorsAndCspReasons`（源码行 61；仅源码登记）
- `retainsOnlyUrlOriginAndRemovesCredentialsPathQueryAndFragment`（源码行 66；仅源码登记）
- `hidesEntireSensitiveHeaderLinesAndFoldedValues`（源码行 72；仅源码登记）
- `hidesShortSecretsInAssignmentsAndQuotedJson`（源码行 80；仅源码登记）
- `hidesOpaqueValuesAndBearerOutsideHeaders`（源码行 90；仅源码登记）
- `hidesNonHttpUrlsAndMalformedUrlPayloads`（源码行 95；仅源码登记）
- `sanitizesBeforeTruncatingAndNormalizesControlCharacters`（源码行 101；仅源码登记）
- `limitsLongMessagesWithoutSplittingSurrogatePairs`（源码行 109；仅源码登记）

### DynamicAlbumSourceTest (5)

源码：[app/src/test/java/com/local/douyinsaver/DynamicAlbumSourceTest.kt](../../app/src/test/java/com/local/douyinsaver/DynamicAlbumSourceTest.kt)

- `selectingLiveAndAnimationKeepsSignedResourcesOrderAndBgm`（源码行 14；仅源码登记）
- `aCoverCannotStandInForMissingOrMarkedMotion`（源码行 26；仅源码登记）
- `actualMotionOverridesAnOmittedUiHintAndOriginalSelectionStaysSeparate`（源码行 36；仅源码登记）
- `renditionSelectionPreservesEveryExplicitKindAndOwnedMotionAddress`（源码行 44；仅源码登记）
- `aDeclaredUnknownDynamicWithoutMotionCannotPassAsAStaticCoverDownload`（源码行 59；仅源码登记）

### FailureMessagesTest (2)

源码：[app/src/test/java/com/local/douyinsaver/FailureMessagesTest.kt](../../app/src/test/java/com/local/douyinsaver/FailureMessagesTest.kt)

- `keepsSpecificHttpReasonButRemovesSignedAddress`（源码行 9；仅源码登记）
- `distinguishesWrappedNetworkAndStorageFailures`（源码行 17；仅源码登记）

### FeaturePolicyTest (7)

源码：[app/src/test/java/com/local/douyinsaver/FeaturePolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/FeaturePolicyTest.kt)

- `albumsKeepOrderWithoutRequiringMusic`（源码行 13；仅源码登记）
- `albumsRequireExactOwnerAndCompleteImages`（源码行 19；仅源码登记）
- `unsupportedFirstUrlsCannotConcealSafeImagesAndMusic`（源码行 25；仅源码登记）
- `newImageOriginsHaveStrictBoundaries`（源码行 31；仅源码登记）
- `directAlbumLinksAndBatchSharesRetainExactIds`（源码行 36；仅源码登记）
- `filenamesAreBoundedSafeAndUnique`（源码行 43；仅源码登记）
- `interruptedTasksRequireRetryAndWaitingOrderIsEditable`（源码行 52；仅源码登记）

### GifClipUiPolicyTest (12)

源码：[app/src/test/java/com/local/douyinsaver/GifClipUiPolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/GifClipUiPolicyTest.kt)

- `ordinaryVideoDefaultsAreSixSecondsFromTheBeginning`（源码行 12；仅源码登记）
- `shorterKnownVideoDefaultsFitTheAvailableRange`（源码行 18；仅源码登记）
- `invalidStoredSettingsHaveBoundedDefaults`（源码行 24；仅源码登记）
- `invalidInputDisablesGifWithoutSilentlyChangingTheDraft`（源码行 33；仅源码登记）
- `knownLengthRejectsOutOfRangeSelectionsAndAllowsExactEnd`（源码行 45；仅源码登记）
- `oneTenthSecondIsTheMinimumExportRange`（源码行 53；仅源码登记）
- `unknownDurationAllowsARequestForTheDownloaderToMeasure`（源码行 58；仅源码登记）
- `staticPicturesAndOriginalAnimationsDoNotOfferFakeGifConversion`（源码行 68；仅源码登记）
- `readableLiveMotionIsRequiredForEveryLiveItemBeforeConversion`（源码行 75；仅源码登记）
- `motionMarksAnItemConvertibleEvenWhenItsKindWasOmitted`（源码行 86；仅源码登记）
- `visibleSecondsUseCompactNumericLabels`（源码行 92；仅源码登记）
- `fullSourceAndRemainderUseActualDurationWithoutAnArbitraryCap`（源码行 98；仅源码登记）

### GifConversionPolicyTest (7)

源码：[app/src/test/java/com/local/douyinsaver/GifConversionPolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/GifConversionPolicyTest.kt)

- `distributesGifDelaysWithoutAccumulatingFpsRoundingError`（源码行 10；仅源码登记）
- `rejectsOutOfBoundsOrOverflowingRangesWithoutSilentTruncation`（源码行 26；仅源码登记）
- `scalesAspectRatioAndDoesNotEnlargeTinyVideo`（源码行 35；仅源码登记）
- `supportsFullVideoRemainderAndOneTenthSecondWithoutAllocatingEveryFrame`（源码行 45；仅源码登记）
- `keepsLowSourceFrameRateAndBoundsGifTemporalPrecision`（源码行 61；仅源码登记）
- `shareModeReducesDetailExplicitlyAndKeepsFullDurationWithoutUpscaling`（源码行 67；仅源码登记）
- `actualFileSizeAdviceDoesNotPretendToKnowChatPlaybackThresholds`（源码行 85；仅源码登记）

### GifEncoderTest (11)

源码：[app/src/test/java/com/local/douyinsaver/GifEncoderTest.kt](../../app/src/test/java/com/local/douyinsaver/GifEncoderTest.kt)

- `writesLoopingTrueRedAndBlueFramesWithExactDelays`（源码行 15；仅源码登记）
- `independentDecoderRecoversNoisyFramesAcrossCodeWidthGrowthAndDictionaryResets`（源码行 41；仅源码登记）
- `independentDecoderRecoversEveryLocalPaletteSizeAndSmallCodeWidthResets`（源码行 59；仅源码登记）
- `compactColorTablesReduceSimpleAnimationWithoutChangingPixelsOrTiming`（源码行 92；仅源码登记）
- `shareModeProducesSmallerDecodedAnimationWithCompleteSelectedTimeline`（源码行 106；仅源码登记）
- `compressesRepeatedColorsInsteadOfMerelyWrappingRawPixels`（源码行 156；仅源码登记）
- `rejectsSingleFrameBadGeometryDelaysAndFileGrowth`（源码行 166；仅源码登记）
- `invokesCancellationCheckpointsDuringPixelQuantizationAndLzwWork`（源码行 182；仅源码登记）
- `retainsAllDarkRedGradientShadesThatFixedRgb332WouldCrush`（源码行 191；仅源码登记）
- `adaptivePaletteReducesPhotographColorErrorComparedToFixedRgb332`（源码行 208；仅源码登记）
- `streamsMoreThanFormerFrameAndDurationCapsWithAccurateDelays`（源码行 240；仅源码登记）

### GifRecordPolicyTest (8)

源码：[app/src/test/java/com/local/douyinsaver/GifRecordPolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/GifRecordPolicyTest.kt)

- `videoAndGifAreIndependentDownloads`（源码行 12；仅源码登记）
- `differentVideoRangesNeverSuppressEachOther`（源码行 20；仅源码登记）
- `embeddedLivePhotoAndConvertedGifCannotSuppressEachOther`（源码行 29；仅源码登记）
- `changingTheStaticImageDefaultCannotReuseAnEarlierAlbumGif`（源码行 56；仅源码登记）
- `aCustomAlbumGifDurationCannotSuppressAutomaticTiming`（源码行 66；仅源码登记）
- `composedAlbumVideosAlsoRequireTheSameAutomaticOrCustomTiming`（源码行 78；仅源码登记）
- `timingUnknownLegacyExportsDoNotSuppressARepeatSave`（源码行 89；仅源码登记）
- `gifHistoryUsesAnimatedPreviewAndCorrectLabel`（源码行 97；仅源码登记）

### MediaProbeTest (36)

源码：[app/src/test/java/com/local/douyinsaver/MediaProbeTest.kt](../../app/src/test/java/com/local/douyinsaver/MediaProbeTest.kt)

- `aForbiddenAwemeColdNodeCanFallBackToAnObservedOfficialMirrorOfTheSameRendition`（源码行 24；仅源码登记）
- `selectedEntryBecomesTheVerifiedFinalUrlUsedByPreviewAndDownloadSelection`（源码行 45；仅源码登记）
- `rejectedCleanCandidateFallsBackWithinTheCleanModeOnly`（源码行 58；仅源码登记）
- `unavailableCleanVersionReportsItsHttpErrorWithoutDownloadingMarked`（源码行 79；仅源码登记）
- `defaultVerificationChecksOnlyTheFirstHealthyCleanAddressAndRetainsRefreshBackups`（源码行 95；仅源码登记）
- `legacyExplicitOriginalRefreshStillStaysUnconfirmed`（源码行 111；仅源码登记）
- `anExpiredFinalUrlCanRefreshFromItsSuccessfulOriginalEntry`（源码行 125；仅源码登记）
- `anUnsafeRedirectIsRejectedBeforeTheNextRequest`（源码行 143；仅源码登记）
- `successfulProbeReadsOnlyTheMp4HeaderAndClosesAnIgnoredRange`（源码行 156；仅源码登记）
- `cancellationCannotBecomeAFallbackToAnotherSource`（源码行 167；仅源码登记）
- `defaultCleanVerificationDoesNotProbeOtherVersionsToCompareTheirRedirects`（源码行 179；仅源码登记）
- `aSelectedCleanRedirectMatchingTheRetainedMarkedFileIsRejectedWithoutSwitching`（源码行 196；仅源码登记）
- `conflictingCleanCandidatesDoNotPreventCheckingAnIndependentThirdSource`（源码行 212；仅源码登记）
- `aHealthyOriginalCannotRescueTheDefaultCleanVerificationWhenItsCleanEntryFails`（源码行 227；仅源码登记）
- `anOriginalSharingTheCleanFileDoesNotCauseAVersionConflict`（源码行 243；仅源码登记）
- `publicMediaEntryStillGetsCheckedAfterSeveralDeadSignedCdnAlternatives`（源码行 252；仅源码登记）
- `selectingUnavailableCleanCannotFallBackToAnAvailableOriginal`（源码行 271；仅源码登记）
- `theProbeEntryQuotaSurvivesMoreThanSixtyFourUnhealthyCdnVariants`（源码行 279；仅源码登记）
- `defaultUnknownOriginalIsRejectedWithoutAnyNetworkRequest`（源码行 296；仅源码登记）
- `selectedCleanVerificationStopsAtItsFirstHealthyUrlInsteadOfCheckingEveryBackup`（源码行 305；仅源码登记）
- `anUnusedClassifiedBackupCanRefreshTheExpiredParsedUrlBeforeSaving`（源码行 316；仅源码登记）
- `theSuccessfulPublicEntryIsRetainedToRefreshItsExpiredFinalUrlBeforeSaving`（源码行 336；仅源码登记）
- `aNonMp4ResponseFallsBackOnlyToAnotherClassifiedCleanSource`（源码行 358；仅源码登记）
- `cancellingTheProbeStopsBeforeReadingOrTryingAnotherCandidate`（源码行 370；仅源码登记）
- `aNewExplicitMarkedRedirectIsRejectedAndOnlyACleanBackupCanRefreshIt`（源码行 384；仅源码登记）
- `aCleanLabelCannotOverrideAnExplicitMarkedInitialAddress`（源码行 402；仅源码登记）
- `aTrustedCdnHttpSecondHopIsUpgradedAndRequestedOnlyThroughHttps`（源码行 413；仅源码登记）
- `numericColdRelayOnItsConfirmedTlsPortVerifiesMp4AndPreservesTheRefreshEntry`（源码行 438；仅源码登记）
- `numericRelayDoesNotMakeWrongPortsMarkedUrlsOrNonMp4ContentValid`（源码行 462；仅源码登记）
- `coldSchedulingSecondHopsConfirmMp4AndKeepTheCleanPlatformRefreshEntry`（源码行 482；仅源码登记）
- `anExpiredSchedulingAddressRefreshesToAnotherDynamicHostWithinTheCleanSource`（源码行 511；仅源码登记）
- `schedulingCdnStillCannotFollowUnknownOrExplicitWatermarkedThirdHops`（源码行 536；仅源码登记）
- `aSchedulingHostnameDoesNotMakeHtmlOrNonMp4ContentAValidVideo`（源码行 551；仅源码登记）
- `aRejectedSecondHopIsDiagnosedWithoutRequestingHttpOrUntrustedTargets`（源码行 561；仅源码登记）
- `anInitialHttpVideoSourceRemainsRejectedWithoutMakingAnyRequest`（源码行 584；仅源码登记）
- `upgradingTrustedHttpDoesNotPermitAnExplicitMarkedRedirect`（源码行 593；仅源码登记）

### MediaTransferTest (14)

源码：[app/src/test/java/com/local/douyinsaver/MediaTransferTest.kt](../../app/src/test/java/com/local/douyinsaver/MediaTransferTest.kt)

- `existingDestinationsIncludingEmptyFilesAreRejectedWithoutRequestsOrDeletion`（源码行 28；仅源码登记）
- `anExistingDirectoryAndItsContentsAreNeverRemoved`（源码行 47；仅源码登记）
- `truncatedResponsesRemoveOnlyTheNewlyOwnedPartialFileAndDisconnect`（源码行 59；仅源码登记）
- `aMarkedInitialOrBackupAddressIsBlockedBeforeAnyConnection`（源码行 75；仅源码登记）
- `aRealGetRedirectToTheKnownMarkedImageIsBlockedBeforeFollowingIt`（源码行 87；仅源码登记）
- `anUnclassifiedFreshTrustedRedirectCanSaveWithoutConflictingWithOriginalMetadata`（源码行 103；仅源码登记）
- `explicitMarkedEndpointAndUnsafeRedirectsAreBothBlockedBeforeTheNextGet`（源码行 119；仅源码登记）
- `bgmUsesTheDefaultCallbackAndStillAllowsTheExistingTrailingProgressLambda`（源码行 136；仅源码登记）
- `transferFollowsTheSameColdSchedulingChainAndPreservesTheSignedTarget`（源码行 146；仅源码登记）
- `sharedTransferSavesTheNumericColdRelayWithTheExactTlsPortAndSignedBytes`（源码行 168；仅源码登记）
- `missingRangeIdentityFallsBackToANewOwnedFullDownload`（源码行 192；仅源码登记）
- `numericRelayDownloadStillBlocksUntrustedPortsAndMarkedRedirectsBeforeRequest`（源码行 210；仅源码登记）
- `aSchedulingTransferCannotFollowAnUnknownOrMarkedNextHopAndLeavesNoFile`（源码行 229；仅源码登记）
- `cancellationDoesNotFollowAnotherGetOrLeaveAPartialFile`（源码行 247；仅源码登记）

### MediaUrlsTest (28)

源码：[app/src/test/java/com/local/douyinsaver/MediaUrlsTest.kt](../../app/src/test/java/com/local/douyinsaver/MediaUrlsTest.kt)

- `acceptsKnownOriginsAndPreservesSignedQuery`（源码行 10；仅源码登记）
- `acceptsHttpsAndHostCaseAndExplicitTlsPort`（源码行 19；仅源码登记）
- `observedS19ColdRelayChainAcceptsOnlyTheExactHttpsOriginAndKeepsRawSignedBytes`（源码行 23；仅源码登记）
- `observedColdRelayExceptionRejectsOtherHostsPortsHttpCredentialsAndIpLiterals`（源码行 41；仅源码登记）
- `observedColdRelayDiagnosticsKeepOnlyTheHostPortAndDecision`（源码行 78；仅源码登记）
- `acceptsOnlyTheConfirmedEightHexColdRelayHostAndTlsPortWithoutChangingItsSignature`（源码行 92；仅源码登记）
- `eightHexRelayExceptionDoesNotAllowOtherHostsPortsSchemesOrCredentials`（源码行 109；仅源码登记）
- `observedS01AndS02NumberedHexRelayKeepsOnlyHttpsTlsAndPreservesSignedBytes`（源码行 139；仅源码登记）
- `observedNumberedRelayRejectsWrongLabelsPortsHttpCredentialsAndPrivateAddresses`（源码行 171；仅源码登记）
- `numericRelayDiagnosticsRetainHostAndPortWithoutRawSignedComponents`（源码行 206；仅源码登记）
- `acceptsRotatingColdSchedulingHostsWithoutRewritingSignedAddresses`（源码行 214；仅源码登记）
- `coldSchedulingRulesRejectApexNestedHostsLookalikesAndUnsafeAuthorities`（源码行 227；仅源码登记）
- `acceptsOnlyTheObservedHashedColdVideoRelayOriginsWithoutChangingSignedBytes`（源码行 238；仅源码登记）
- `acceptsTheConfirmed48HexColdVideoRelayWithoutRewritingItsSignedAddress`（源码行 250；仅源码登记）
- `hashedColdRelayLengthsRemainExactly32Or48AndRejectAllOtherObservedBoundaries`（源码行 265；仅源码登记）
- `hashedRelayRulesRejectApexOtherSubdomainsIncorrectHashesAndUnsafeAuthorities`（源码行 276；仅源码登记）
- `theObservedColdRelayChainAndRelativeRedirectsRetainTheOriginalSignature`（源码行 289；仅源码登记）
- `aTrustedRelayHttpLocationRequestsHttpsWithoutChangingRawComponentsOrTrustRules`（源码行 305；仅源码登记）
- `aTrustedSchedulingHttpLocationUsesHttpsWithTheSameSignatureButNotOtherPorts`（源码行 320；仅源码登记）
- `acceptsPublicPlaybackEntryWithoutBroadeningOtherServices`（源码行 329；仅源码登记）
- `rejectsLookalikeDomainsAndEmbeddedCredentials`（源码行 343；仅源码登记）
- `rejectsUnsafeSchemesPortsAndMalformedUrls`（源码行 356；仅源码登记）
- `rejectsIpAddressesAndLocalNetworkOrigins`（源码行 366；仅源码登记）
- `followsOnlyValidatedRedirectsAndDoesNotExposeMalformedAddress`（源码行 376；仅源码登记）
- `trustedHttpRedirectsUpgradeToHttpsWithoutChangingRawSignedComponents`（源码行 388；仅源码登记）
- `redirectDiagnosticsShowRejectedTargetsWithoutPathsQueriesOrCredentials`（源码行 398；仅源码登记）
- `trustedHttpUpgradeStillRefusesOtherPortsAndUntrustedOrLookalikeHosts`（源码行 410；仅源码登记）
- `compatibleRedirectLogsTheOriginalHttpTargetAndItsValidatedHttpsUpgrade`（源码行 420；仅源码登记）

### MotionPhotoCodecPolicyTest (4)

源码：[app/src/test/java/com/local/douyinsaver/MotionPhotoCodecPolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/MotionPhotoCodecPolicyTest.kt)

- `allowsAllThreeOfficialMotionPhotoVideoCodecs`（源码行 8；仅源码登记）
- `rejectsOtherDecodableVideoCodecsRatherThanAssumingGalleryCompatibility`（源码行 15；仅源码登记）
- `rejectsContainerMimeAudioUnknownAndSimilarNames`（源码行 24；仅源码登记）
- `failureExplainsCompatibilityAndKeepsOriginalResourceAsTheSaveChoice`（源码行 34；仅源码登记）

### MotionPhotoContainerTest (13)

源码：[app/src/test/java/com/local/douyinsaver/MotionPhotoContainerTest.kt](../../app/src/test/java/com/local/douyinsaver/MotionPhotoContainerTest.kt)

- `singleFileContainsUntouchedExifJpegScanAndOriginalMp4Bytes`（源码行 29；仅源码登记）
- `xmpIsNamespaceAwareValidXmlWithBothModernAndObservedLegacyOffsets`（源码行 49；仅源码登记）
- `keepsUnrelatedMetadataAndReplacesConflictingOldMotionFlagsOnce`（源码行 66；仅源码登记）
- `readsTheObservedDouyinLegacyJpegAndExtractsAllOriginalVideoBytes`（源码行 77；仅源码登记）
- `plainJpegHasNoMotionAndAnExplicitZeroFlagSuppressesLegacy`（源码行 88；仅源码登记）
- `rejectsTruncatedOffsetAudioOrHtmlMasqueradingAsEmbeddedVideo`（源码行 94；仅源码登记）
- `rejectsDuplicateConflictingOffsetsInsteadOfChoosingOne`（源码行 102；仅源码登记）
- `neverOverwritesInputsOrExistingOutputs`（源码行 107；仅源码登记）
- `rejectsAlreadyAppendedMediaHdrAndExternalEntityWithoutCreatingDestination`（源码行 117；仅源码登记）
- `cancellationDuringStreamingRemovesPartialNewFileAndKeepsSources`（源码行 128；仅源码登记）
- `supportsProgressiveScansWithoutConfusingStuffedOrRestartMarkers`（源码行 138；仅源码登记）
- `namesSatisfyAndroidMotionPhotoSuffixPattern`（源码行 146；仅源码登记）
- `xiaomiProfileRequiresActualCoverTimeAndPreservesCompressedPixelsAndMotionBytes`（源码行 151；仅源码登记）

### MotionPhotoExifTest (4)

源码：[app/src/test/java/com/local/douyinsaver/MotionPhotoExifTest.kt](../../app/src/test/java/com/local/douyinsaver/MotionPhotoExifTest.kt)

- `newExifCreatesOneByteMicroVideoFlagWithoutInventingCameraOrCaptureMetadata`（源码行 11；仅源码登记）
- `appendingAnExifFlagPreservesOpaqueBytesOffsetsThumbnailChainAndBothByteOrders`（源码行 30；仅源码登记）
- `replacingAnExistingFlagDoesNotDuplicateOrRelocateExifDirectories`（源码行 67；仅源码登记）
- `malformedOrDuplicateExifPointersAreRejectedRatherThanDroppingMetadata`（源码行 73；仅源码登记）

### OfficialPhotoClipPolicyTest (6)

源码：[app/src/test/java/com/local/douyinsaver/OfficialPhotoClipPolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/OfficialPhotoClipPolicyTest.kt)

- `anOlderUntaggedPhotoCanStillProvideItsOwnVideo`（源码行 7；仅源码登记）
- `onlyOfficialVideoLiveAndDefaultClipsCanUseTaggedVideoMetadata`（源码行 12；仅源码登记）
- `anOfficialImageClipCannotTurnItsVideoPlaceholderIntoMotion`（源码行 20；仅源码登记）
- `aDeclaredLivePhotoRemainsLiveEvenBeforeItsVideoIsAvailable`（源码行 25；仅源码登记）
- `guessedUnknownAndCoercedClipTagsCannotProvideVideoOrAFalseLiveDeclaration`（源码行 30；仅源码登记）
- `jsonNumericRepresentationsHaveTheSameMeaningAsJavascriptNumbers`（源码行 37；仅源码登记）

### PageNetworkFailuresTest (8)

源码：[app/src/test/java/com/local/douyinsaver/PageNetworkFailuresTest.kt](../../app/src/test/java/com/local/douyinsaver/PageNetworkFailuresTest.kt)

- `genericEmptyShareMayRetryOnlyOnceForTheExactConfirmedWork`（源码行 14；仅源码登记）
- `emptyPageRecoveryDoesNotRetryKnownPrivateDeletedOrVerificationPages`（源码行 22；仅源码登记）
- `transientSameWorkRetryIsBoundedToOneAttempt`（源码行 29；仅源码登记）
- `tlsAndNonTransportErrorsNeverRetry`（源码行 37；仅源码登记）
- `retryCannotEscapeWorkOriginOrHttpsBoundary`（源码行 41；仅源码登记）
- `wrappedTransportErrorsKeepTheirActualCategory`（源码行 50；仅源码登记）
- `allFailedSourcesDoNotHideKnownDnsFailureAsExpiredWork`（源码行 58；仅源码登记）
- `unknownServerStringsAndUrlsDoNotBecomeDisplayedNetworkReasons`（源码行 66；仅源码登记）

### PickerColorTest (5)

源码：[app/src/test/java/com/local/douyinsaver/PickerColorTest.kt](../../app/src/test/java/com/local/douyinsaver/PickerColorTest.kt)

- `hueWheelIncludesEachPrimaryAndSecondaryColor`（源码行 7；仅源码登记）
- `importedLegacyRgbColorsRoundTripExactly`（源码行 14；仅源码登记）
- `panelEdgesAllowWhiteBlackAndFullySaturatedHue`（源码行 25；仅源码登记）
- `achromaticRgbPreservesPreviousHueAndBlackPreservesSaturation`（源码行 32；仅源码登记）
- `finiteClampAndHueWrapKeepTouchPositionsValid`（源码行 44；仅源码登记）

### PlayerCandidatePolicyTest (6)

源码：[app/src/test/java/com/local/douyinsaver/PlayerCandidatePolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/PlayerCandidatePolicyTest.kt)

- `acceptsObservedWorkOnlyAfterMetadataIsReady`（源码行 9；仅源码登记）
- `rejectsPrecreatedPlayerAndZeroSizedPlaceholders`（源码行 20；仅源码登记）
- `rejectsMissingInvalidAndNonFiniteDuration`（源码行 28；仅源码登记）
- `rejectsRecommendationsAndOtherPageIds`（源码行 34；仅源码登记）
- `rejectsUntrustedOrUnsupportedMediaDespiteValidMetadata`（源码行 42；仅源码登记）
- `normalizesTitleWithoutChangingVideoIdentity`（源码行 53；仅源码登记）

### PreviewPlaybackPolicyTest (6)

源码：[app/src/test/java/com/local/douyinsaver/PreviewPlaybackPolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/PreviewPlaybackPolicyTest.kt)

- `customSeekSecondsKeepTheSupportedRange`（源码行 7；仅源码登记）
- `independentSeekStepsUseSecondsAndClampAtBothEnds`（源码行 16；仅源码登记）
- `timeLabelsKeepDigitsStableAcrossMinuteAndHourBoundaries`（源码行 25；仅源码登记）
- `seekButtonsCannotLeaveThePlayableTimeline`（源码行 32；仅源码登记）
- `invalidOrOverflowingSeekValuesStayBounded`（源码行 39；仅源码登记）
- `sourceAspectRatiosSupportPortraitLandscapeAndUnknownMetadata`（源码行 45；仅源码登记）

### QueuePolicyTest (5)

源码：[app/src/test/java/com/local/douyinsaver/QueuePolicyTest.kt](../../app/src/test/java/com/local/douyinsaver/QueuePolicyTest.kt)

- `dragKeepsStableIdentityAndOnlyCrossesUnstartedTasks`（源码行 9；仅源码登记）
- `availableParsedResultsIncludeSavedTasksForSavingAgainAndIgnoreUnknownKeys`（源码行 17；仅源码登记）
- `restartExpiresOnlyTransientStatesAndNeverReopensSavedTasks`（源码行 25；仅源码登记）
- `nextParsingSkipsReadyAlbumsFailuresAndSavedItems`（源码行 37；仅源码登记）
- `restoringObsoleteVersionHintsKeepsTaskIdentityAndStatus`（源码行 44；仅源码登记）

### ShareLinksTest (6)

源码：[app/src/test/java/com/local/douyinsaver/ShareLinksTest.kt](../../app/src/test/java/com/local/douyinsaver/ShareLinksTest.kt)

- `extractsRealShareTextAndHyphens`（源码行 7；仅源码登记）
- `rejectsLookalikeDomainsAndCredentials`（源码行 12；仅源码登记）
- `preservesIdsWithoutNumericRounding`（源码行 18；仅源码登记）
- `rejectsMultipleDistinctLinks`（源码行 24；仅源码登记）
- `resolvingAnAlreadyKnownOfficialNoteRetainsItsCompleteShareContext`（源码行 28；仅源码登记）
- `resolvingAKnownWorkStillRejectsAnUntrustedOrInsecureContext`（源码行 34；仅源码登记）

### VideoRangeTransferTest (19)

源码：[app/src/test/java/com/local/douyinsaver/VideoRangeTransferTest.kt](../../app/src/test/java/com/local/douyinsaver/VideoRangeTransferTest.kt)

- `onlyTheConfirmedRelayIsEligibleForOptInRangeDownloads`（源码行 30；仅源码登记）
- `multipleSegmentsKeepEveryOriginalByteAndRawSignatureAndUseStrongIfRange`（源码行 39；仅源码登记）
- `aTruncatedSegmentIsRetriedWithoutAppendingAnyOfItsPartialBytes`（源码行 64；仅源码登记）
- `repeatedTruncationIsLimitedToTwoRetriesAndDeletesTheOwnedPartialFile`（源码行 81；仅源码登记）
- `weakMissingAndMalformedEtagsNeverPermitMultipleResponseAssembly`（源码行 93；仅源码登记）
- `aChangedOrMissingStrongValidatorOnALaterSegmentRejectsTheWholeFile`（源码行 107；仅源码登记）
- `invalidRangeStartEndTotalAndContentLengthAreRejectedBeforeReadingOrAppending`（源码行 127；仅源码登记）
- `aChangedTotalOnALaterSegmentRejectsPreviouslyCommittedData`（源码行 153；仅源码登记）
- `totalsAboveTheConfiguredLimitAreRejectedWithoutReading`（源码行 168；仅源码登记）
- `aRangeIgnoredFromTheBeginningCanSaveOnlyAnEntire200Response`（源码行 179；仅源码登记）
- `a200AfterPartialSegmentsRestartsAtZeroAndNeverAppendsTheWholeFile`（源码行 188；仅源码登记）
- `anIncomplete200ReplacementCannotLeaveOldOrPartiallyReplacedBytes`（源码行 201；仅源码登记）
- `encodedHtmlNonMp4AndBodiesLongerThanTheRangeAreRejected`（源码行 214；仅源码登记）
- `redirectsUseTheSharedTrustRulesAndTheSelectedRenditionGuardBeforeAnyNextRequest`（源码行 233；仅源码登记）
- `anAllowedRedirectPreservesTheRangeAndGuardWithoutChangingSignedBytes`（源码行 251；仅源码登记）
- `explicitCancellationDisconnectsAndDoesNotCommitOrKeepThePartialFile`（源码行 266；仅源码登记）
- `parentCancellationAfterACommittedSegmentStopsBeforeAnotherRequestAndDeletesTheTempFile`（源码行 279；仅源码登记）
- `anExistingNonemptyFileIsNeverTruncatedOrDeleted`（源码行 292；仅源码登记）
- `callbacksAndLocalWritesDoNotBecomeRemoteRetryAttempts`（源码行 304；仅源码登记）

### VideoSourceFallbackTest (9)

源码：[app/src/test/java/com/local/douyinsaver/VideoSourceFallbackTest.kt](../../app/src/test/java/com/local/douyinsaver/VideoSourceFallbackTest.kt)

- `interruptedTransferCleansItsAttemptBeforeTryingAnotherUrlOfTheRequestedMode`（源码行 19；仅源码登记）
- `outputWriteFailureDoesNotRepeatTheDownload`（源码行 41；仅源码登记）
- `directoryPermissionFailureDoesNotTryAnotherServer`（源码行 54；仅源码登记）
- `cleanupFailureAfterAnInterruptedTransferStopsInsteadOfLeavingAnotherPartialFile`（源码行 65；仅源码登记）
- `failureAfterSavingHasStartedCannotPublishTheSameDownloadTwice`（源码行 83；仅源码登记）
- `aSavedRecordFailureDoesNotStartAnotherTransfer`（源码行 99；仅源码登记）
- `explicitCancellationStopsBeforeTheNextCandidate`（源码行 114；仅源码登记）
- `coroutineCancellationCannotBeHiddenBehindATransferError`（源码行 125；仅源码登记）
- `exhaustedAlternativesKeepTheSelectedModeAndHttpReason`（源码行 139；仅源码登记）

### WatermarkSourcesTest (30)

源码：[app/src/test/java/com/local/douyinsaver/WatermarkSourcesTest.kt](../../app/src/test/java/com/local/douyinsaver/WatermarkSourcesTest.kt)

- `observedOfficialMobilePlaybackMirrorKeepsItsMediaIdentityAndSignedParameters`（源码行 13；仅源码登记）
- `arbitraryOfficialPagesAndOtherPlaybackOriginsCannotInheritTheMirrorRole`（源码行 27；仅源码登记）
- `publicPlaybackEntryBuildsTwoVersionsWithoutChangingTheMediaIdentity`（源码行 39；仅源码登记）
- `playbackQueryRequiresExactlyOneValidMediaIdAndExactTrustedOrigin`（源码行 51；仅源码登记）
- `independentSourcesStaySignedAndSelectingCleanDoesNotMutateOriginal`（源码行 59；仅源码登记）
- `loneUnknownCdnOrIdenticalTwoFieldsCannotMasqueradeAsClean`（源码行 67；仅源码登记）
- `contradictoryWatermarkFlagsAndArbitraryFilenamesAreNotProofOfClean`（源码行 79；仅源码登记）
- `fieldRolesDoNotOverrideAnExplicitPlaybackRenditionOrConflictingFlags`（源码行 84；仅源码登记）
- `explicitWatermarkUrlsKeepBothFieldsUnchanged`（源码行 92；仅源码登记）
- `anExplicitImageTransformCannotBeOverriddenByAContradictoryQuery`（源码行 100；仅源码登记）
- `aPlaybackEntryWithoutWatermarkSwitchDoesNotInventAnExtraParameter`（源码行 106；仅源码登记）
- `aKnownCleanOnlySourceCannotBeOfferedAsTheWatermarkedVersion`（源码行 113；仅源码登记）
- `anObservedDomPlayerCannotBeDeclaredCleanByAnUnrelatedDownloadRole`（源码行 120；仅源码登记）
- `retainedUnconfirmedBackupDoesNotInheritTheOtherCandidatesWatermarkLabel`（源码行 127；仅源码登记）
- `exactOpaqueVideoMediaIdsAddBothPlatformRenditionsWithoutInventingAnOriginalUrl`（源码行 138；仅源码登记）
- `workNumbersUrlsAndUntrustedUriShapesCannotBecomeVideoMediaIds`（源码行 147；仅源码登记）
- `allTrustedRawPlaybackDownloadAndDomUrlsRemainAvailableAsOriginals`（源码行 152；仅源码登记）
- `aFullPlaybackFieldDoesNotDiscardTheOtherFieldsOriginalSources`（源码行 159；仅源码登记）
- `aFullCdnFieldCannotTruncateThePlatformEntryGeneratedFromItsOwnedMediaUri`（源码行 169；仅源码登记）
- `transferRejectsKnownOtherRenditionsEvenWithAnAddedFragment`（源码行 178；仅源码登记）
- `transferRejectsExplicitMarkedEntriesFlagsAndImageTransforms`（源码行 187；仅源码登记）
- `transferDoesNotTreatUnclassifiedOriginalsAsAConflictingVersion`（源码行 203；仅源码登记）
- `transferKeepsSignedCleanUrlsAndDoesNotGuessFromFilenames`（源码行 211；仅源码登记）
- `transferStillRejectsUntrustedOriginsForAllLegacyModes`（源码行 217；仅源码登记）
- `missingSourceErrorsOnlyAskToParseAgain`（源码行 225；仅源码登记）
- `knownLiveWithoutSeparateMotionHasOnlyPendingDownloadEligibility`（源码行 240；仅源码登记）
- `pendingLiveDownloadRetainsMixedOrderIdentityAndStrictNeighbourSelection`（源码行 264；仅源码登记）
- `pendingLiveRequiresAnExactTrustedNonconflictingCleanImageSource`（源码行 286；仅源码登记）
- `pendingLiveDoesNotPermitOtherKindsIncompleteMotionOrOtherRenditions`（源码行 312；仅源码登记）
- `downloadOnlySelectionDoesNotChangeAnyCompleteOrdinarySourceBehaviour`（源码行 332；仅源码登记）

## Android

### AlbumCompositionTest (5)

源码：[app/src/androidTest/java/com/local/douyinsaver/AlbumCompositionTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/AlbumCompositionTest.kt)

条件边界：存在assume条件调用（源码行 167, 174），须另核实opt-in/实际执行条件。

- `cancelledCompositionKeepsCallerFilesAndRemovesOwnedIntermediates`（源码行 34；仅源码登记）
- `composesOrderedImagesWithLoopingShortAudio`（源码行 55；仅源码登记）
- `savesOriginalImagesToDefaultMediaStore`（源码行 108；仅源码登记）
- `cancelledImageSaveRemovesOnlyItsNewPendingFiles`（源码行 145；仅源码登记）
- `savesOriginalImagesToUserSelectedTestFolder`（源码行 165；仅源码登记）

### AlbumGifComposerTest (4)

源码：[app/src/androidTest/java/com/local/douyinsaver/AlbumGifComposerTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/AlbumGifComposerTest.kt)

- `exportsTwoStaticImagesAtOneTenthSecondWithCorrectOrderAndAspectRatio`（源码行 26；仅源码登记）
- `retainsDifferentPerImageTimingsAndDoesNotOverwriteExistingOutput`（源码行 60；仅源码登记）
- `cancellationRemovesOnlyItsPartialOutputAndRetainsBothSources`（源码行 69；仅源码登记）
- `rejectsAnimatedSourcesAndOneStillWithoutCreatingFakeAnimation`（源码行 83；仅源码登记）

### AlbumGifRollbackTest (2)

源码：[app/src/androidTest/java/com/local/douyinsaver/AlbumGifRollbackTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/AlbumGifRollbackTest.kt)

- `cancellationAfterSecondItemEncodesItsFirstFrameRollsBackBothGifs`（源码行 27；仅源码登记）
- `ioFailureAfterSecondItemEncodesItsFirstFrameRollsBackBothGifs`（源码行 31；仅源码登记）

### AlbumMotionAppPipelineTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/AlbumMotionAppPipelineTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/AlbumMotionAppPipelineTest.kt)

条件边界：存在assume条件调用（源码行 60），须另核实opt-in/实际执行条件。

- `explicitlyRequestedAlbumChecksSourcesAndSavesItsChosenDynamicFormatThroughTheApp`（源码行 58；仅源码登记）

### AlbumMotionEngineTest (39)

源码：[app/src/androidTest/java/com/local/douyinsaver/AlbumMotionEngineTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/AlbumMotionEngineTest.kt)

- `unavailableAndFailedReadsKeepTheReadyAlbumAndItsIndependentOptions`（源码行 23；仅源码登记）
- `aCompleteCandidateEnrichesOnlyItsRequestedQueueWorkAndRestoresTheSingleResult`（源码行 63；仅源码登记）
- `readyAnimatedGifAndWebpHonorExplicitGifChoiceWithoutADesktopRead`（源码行 113；仅源码登记）
- `knownMotionSourcesHonorTheFormatOnBothSingleAndBatchCardsWithoutARead`（源码行 153；仅源码登记）
- `newlyReadOriginalAndGifExportsHaveIndependentDuplicateChecks`（源码行 188；仅源码登记）
- `aPendingReadKeepsItsRequestedGifFormatAcrossVerification`（源码行 229；仅源码登记）
- `explicitGifRemainsAvailableInFailedShareRecovery`（源码行 254；仅源码登记）
- `coversModeUsesTheSeparateDownloadActionAndCannotStartAMotionRead`（源码行 272；仅源码登记）
- `incompleteWrongWorkAndUnclassifiedCandidatesNeverReachSaving`（源码行 292；仅源码登记）
- `aCancelledNonceCannotCompleteOrCancelTheNextReadOfTheSameWork`（源码行 320；仅源码登记）
- `clearingTheQueueAndChangingTheInputRetireLateMotionCallbacks`（源码行 346；仅源码登记）
- `removingTheRequestedTaskCannotResurrectItWithALateCandidate`（源码行 377；仅源码登记）
- `generationQueueEpochAndBaseReferenceEachGuardAnOtherwiseCurrentNonce`（源码行 399；仅源码登记）
- `detachingOnlyTheActiveHostRetiresItsReadAndPreservesTheOriginalAlbum`（源码行 432；仅源码登记）
- `aVerificationGateCanBeCancelledWithoutDiscardingTheReadyImages`（源码行 457；仅源码登记）
- `aSynchronousReadFailureStillAllowsTheSingleAlbumToSaveAsOriginalImages`（源码行 486；仅源码登记）
- `aFailedMotionReadStillAllowsAnOriginalImageBatchToContinuePastSaveFailure`（源码行 511；仅源码登记）
- `aKnownFailedShareCanRecoverACompleteCandidateAndSaveOriginalMotionByDefault`（源码行 547；仅源码登记）
- `failedShareUnavailableFailureAndCancellationKeepTheFailureWithoutHistory`（源码行 575；仅源码登记）
- `unknownFailedShareIdsAndLateResultsAfterClearInputCannotStartRecovery`（源码行 606；仅源码登记）
- `checkingDynamicSourcesOnlyEnrichesTheMatchingSingleWorkAndDoesNotSave`（源码行 636；仅源码登记）
- `checkingQueueDynamicSourcesLeavesItsNeighborAndSingleWorkUntouchedWithoutSaving`（源码行 667；仅源码登记）
- `unknownDynamicRequiresAChoiceOnSingleAndQueueCardsAndRetainsKnownTypes`（源码行 688；仅源码登记）
- `selectedPhotoFollowsItsExactIdentityAfterDesktopArrayReordering`（源码行 727；仅源码登记）
- `failedShareCheckRecoversKnownLiveAsReadyWithoutSavingUntilUserRequestsIt`（源码行 750；仅源码登记）
- `parsingAutomaticallyReadsTheSameAlbumAndPromotesMotionWithoutSaving`（源码行 780；仅源码登记）
- `automaticVerificationOrTimeoutKeepsCoversAndReleasesTheRead`（源码行 802；仅源码登记）
- `declaredLiveWithoutAMobileClipAutomaticallyRecoversWithoutSavingItsCoverAsLive`（源码行 818；仅源码登记）
- `failedDeclaredLiveRecoveryRetainsItsTypeButCannotDownloadAStillAsLive`（源码行 851；仅源码登记）
- `automaticSingleAndQueueRecoveryRetainKnownKindsByUniquePhotoIdentityAfterDesktopReordering`（源码行 900；仅源码登记）
- `missingLiveRecoveryStillRejectsUntrustedCoversOtherMissingDynamicAssetsAndSourceLessVideos`（源码行 953；仅源码登记）
- `missingLiveRecoveryFailureDoesNotBlockQueueParsingOrItsValidNeighborsSaving`（源码行 977；仅源码登记）
- `knownNativeAnimationNeedsNoExtraDesktopReadDuringParsing`（源码行 1025；仅源码登记）
- `clearingInputRetiresAnAutomaticReadAndRejectsItsLateCandidate`（源码行 1040；仅源码登记）
- `pausingAnAutomaticQueueReadReleasesBusyAndIgnoresItsLateCandidate`（源码行 1056；仅源码登记）
- `automaticVerificationInAQueueDoesNotBlockTheNextWork`（源码行 1089；仅源码登记）
- `knownLivePendingFileUsesDownloadQualificationWithoutClaimingMotionReadSuccess`（源码行 1114；仅源码登记）
- `batchPendingLiveKeepsItsQueueIdentityAndDoesNotBlockTheNextDownloadFixture`（源码行 1139；仅源码登记）
- `unknownDynamicWithoutAMotionCannotUseTheEmbeddedLiveDownloadException`（源码行 1164；仅源码登记）

### AnimatedImageVideoConverterTest (10)

源码：[app/src/androidTest/java/com/local/douyinsaver/AnimatedImageVideoConverterTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/AnimatedImageVideoConverterTest.kt)

- `readsActualGifWebpAndApngTimelinesAndFrameOrderWithoutClockCapture`（源码行 37；仅源码登记）
- `gifFramesSwitchAtExactShortDelayBoundariesWithoutMovieNormalization`（源码行 58；仅源码登记）
- `apngFractionalDelaysUseCumulativeBoundariesWithoutTimelineDrift`（源码行 79；仅源码登记）
- `apngPositiveSubMillisecondDelaysAccumulateWithoutPerFrameMinimum`（源码行 95；仅源码登记）
- `apngZeroNumeratorKeepsExplicitMinimumWhileZeroDenominatorUsesHundredths`（源码行 107；仅源码登记）
- `gifSubframesKeepTransparencyOffsetsAndRestoreBackgroundOrPreviousPixels`（源码行 120；仅源码登记）
- `convertsEveryNativeAnimationToDecodableSilentMp4AndKeepsSourceBytes`（源码行 142；仅源码登记）
- `preservesHdAnimationGeometryAndDecodedColorsBeforeBgmComposition`（源码行 177；仅源码登记）
- `cancellationDeletesOnlyItsPartialMp4AndRetainsAnimationSource`（源码行 209；仅源码登记）
- `rejectsStaticOrTruncatedContainersWithoutOverwritingExistingOutput`（源码行 229；仅源码登记）

### BatchQueueInteractionTest (7)

源码：[app/src/androidTest/java/com/local/douyinsaver/BatchQueueInteractionTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/BatchQueueInteractionTest.kt)

- `buttonsLongPressAndBothSwipeDirectionsPreserveTaskIdentity`（源码行 62；仅源码登记）
- `resultCardsKeepAllSourcesAndLockSavingDuringParsing`（源码行 89；仅源码登记）
- `heldDragAtListEdgeScrollsAndMovesBeyondInitialViewport`（源码行 127；仅源码登记）
- `clearingAllTasksRequiresConfirmationAndPreservesSingleContentAndHistory`（源码行 145；仅源码登记）
- `missingCleanSourcesDisableSavingWithoutOfferingOtherModes`（源码行 218；仅源码登记）
- `batchHeaderShowsOnlyDownloadActionsAndLocksWhileProcessing`（源码行 236；仅源码登记）
- `completedCardsCanSaveAgainWithoutReparsing`（源码行 256；仅源码登记）

### BrowserParserNetworkTest (5)

源码：[app/src/androidTest/java/com/local/douyinsaver/BrowserParserNetworkTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/BrowserParserNetworkTest.kt)

- `genericEmptyShareReloadsTheExactUrlAndSharesTheTransportRetryBudget`（源码行 22；仅源码登记）
- `sameWorkDnsFailureRetriesOnceThenReportsDnsInsteadOfMissingWork`（源码行 37；仅源码登记）
- `cancellingPendingRetryCannotLoadAgainOrReportFailure`（源码行 49；仅源码登记）
- `failedSecondaryResourceDoesNotRetryOrAbortMainDocument`（源码行 56；仅源码登记）
- `tlsFailureCannotTriggerRetryOrWeakenSecurity`（源码行 64；仅源码登记）

### ColdCdnPipelineTest (4)

源码：[app/src/androidTest/java/com/local/douyinsaver/ColdCdnPipelineTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/ColdCdnPipelineTest.kt)

- `numericColdRelayCanProbeTransferAndDecodeAnOriginalMp4OnItsConfirmedTlsPort`（源码行 35；仅源码登记）
- `confirmedColdCdnSourceCanRefreshThroughTheSharedTransferAndDecodeLocally`（源码行 82；仅源码登记）
- `coldCdnProbeDoesNotRequestAnUnknownOrWatermarkedThirdRedirect`（源码行 148；仅源码登记）
- `sharedTransferRejectsAChangedSourceAndLeavesNoPartialFile`（源码行 174；仅源码登记）

### ContentDownloaderEmbeddedLiveTest (5)

源码：[app/src/androidTest/java/com/local/douyinsaver/ContentDownloaderEmbeddedLiveTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/ContentDownloaderEmbeddedLiveTest.kt)

- `knownLiveEmbeddedJpegPassesContentEntryAndKeepsEveryOriginalByteInAllNormalModes`（源码行 32；仅源码登记）
- `individualNativeLiveSaveDoesNotFetchNeighboursAndRetainsOriginalIndex`（源码行 77；仅源码登记）
- `liveStaticCoverFailsThroughContentEntryBeforeAllNormalOrDerivedPublication`（源码行 99；仅源码登记）
- `animatedVideoCoverStillFailsInsteadOfUsingPendingLiveEligibility`（源码行 127；仅源码登记）
- `explicitCoverDownloadRemainsStaticAndVideoGifKeepsStrictVerifiedVideoPath`（源码行 149；仅源码登记）

### CustomColorPickerTest (2)

源码：[app/src/androidTest/java/com/local/douyinsaver/CustomColorPickerTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/CustomColorPickerTest.kt)

- `paletteGesturesKeepHueAtWhiteAndBlackWithoutHexEditor`（源码行 40；仅源码登记）
- `selectedColorsAndLegacyHexPersistOnlyInAnIsolatedStore`（源码行 91；仅源码登记）

### DesktopAlbumPublicSampleTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/DesktopAlbumPublicSampleTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/DesktopAlbumPublicSampleTest.kt)

条件边界：存在assume条件调用（源码行 98），须另核实opt-in/实际执行条件。

- `inspectExplicitDesktopAlbumAndVerifyItsOwnMotion`（源码行 95；仅源码登记）

### DesktopAlbumResolverLifecycleTest (11)

源码：[app/src/androidTest/java/com/local/douyinsaver/DesktopAlbumResolverLifecycleTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/DesktopAlbumResolverLifecycleTest.kt)

- `staticObservationDoesNotSucceedAndOrderedMotionNeedsTwoStablePolls`（源码行 29；仅源码登记）
- `optInCompleteStaticAndNativeAnimatedArraysWaitForHydrationAndTwoStableObservations`（源码行 60；仅源码登记）
- `anOptInStillObservationDoesNotWinBeforeALateOwnedMotionAppearsDuringHydration`（源码行 84；仅源码登记）
- `theDefaultDynamicReaderStillRejectsACompleteStaticAlbumAfterHydration`（源码行 104；仅源码登记）
- `evenOptInCannotTreatAMissingLiveOrUnknownDynamicClipAsAStaticSuccess`（源码行 118；仅源码登记）
- `ordinaryVideoFallbackRequiresOwnedVideoDataAndTwoStablePollsRatherThanBgmOrAnotherWork`（源码行 139；仅源码登记）
- `emptyCompleteVideoPageStillAcceptsLaterOwnedHydrationAfterTheGraceBoundary`（源码行 161；仅源码登记）
- `missingPosterWrongIndexAndWrongOwnerCannotProduceCandidates`（源码行 179；仅源码登记）
- `globalTruncationDoesNotHideACompleteOwnedArray`（源码行 189；仅源码登记）
- `onlyAnActualGatePausesAndManualVerificationCanResumeTheSameWork`（源码行 204；仅源码登记）
- `cancelledAndDisposedControllersIgnorePreviouslyQueuedJavascriptCallbacks`（源码行 241；仅源码登记）

### DesktopShareFallbackEngineTest (14)

源码：[app/src/androidTest/java/com/local/douyinsaver/DesktopShareFallbackEngineTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/DesktopShareFallbackEngineTest.kt)

- `dnsFailureRemainsNetworkFailureAfterUnsuccessfulAlternatePage`（源码行 28；仅源码登记）
- `dnsFailureDoesNotBlockVerifiedSameWorkAlternateSource`（源码行 46；仅源码登记）
- `emptyMobileAndApiRecoverACompleteMixedAlbumOnceWithoutSavingOrAnotherDesktopRead`（源码行 64；仅源码登记）
- `wrongWorkBgmOnlyAndIncompleteUntrustedAssetsCannotBecomeRecoveredContent`（源码行 95；仅源码登记）
- `aCompleteStaticAlbumAndNativeAnimationRecoverWithoutPretendingToHaveMotionOrReadingAgain`（源码行 129；仅源码登记）
- `aSameWorkDesktopVideoMustPassTheActualByteProbeBeforeItBecomesReady`（源码行 159；仅源码登记）
- `aRejectedDesktopVideoCannotRetryTheSamePageOrBlockTheNextQueueWork`（源码行 201；仅源码登记）
- `cancellationReentryAndSupersedingInputRejectOldDesktopCallbacks`（源码行 242；仅源码登记）
- `aDetachedHostRetiresItsCallbackAndAllowsTheInterruptedSameWorkToRetry`（源码行 276；仅源码登记）
- `anActualVerificationGateKeepsTheShareIdAndExistingManualCheckEntryWithoutSaving`（源码行 306；仅源码登记）
- `failedUnavailableVerificationAndSynchronousExceptionsContinueTheQueueAndPreserveItsSingleResult`（源码行 337；仅源码登记）
- `aRecoveredQueueAlbumIsReadyAndAvailableBeforeTheNextWorkWithoutAnotherRead`（源码行 375；仅源码登记）
- `pausingTheQueueRetiresThePendingFallbackAndLateResultsCannotResurrectIt`（源码行 406；仅源码登记）
- `anAvailableApiNativeAnimationWinsWithoutCheckingTheFailedShareDesktopRoute`（源码行 438；仅源码登记）

### DynamicAlbumPreviewTest (3)

源码：[app/src/androidTest/java/com/local/douyinsaver/DynamicAlbumPreviewTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/DynamicAlbumPreviewTest.kt)

- `originalGifActuallyAnimatesPausesAndReleases`（源码行 46；仅源码登记）
- `originalWebpActuallyAnimatesPausesAndReleases`（源码行 50；仅源码登记）
- `mediaStoreGifCanBeDeletedWhileItsPrivatePreviewKeepsAnimating`（源码行 54；仅源码登记）

### DynamicAlbumPublicSampleTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/DynamicAlbumPublicSampleTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/DynamicAlbumPublicSampleTest.kt)

条件边界：存在assume条件调用（源码行 29），须另核实opt-in/实际执行条件。

- `inspectPublicAlbumWithoutSaving`（源码行 26；仅源码登记）

### DynamicAlbumRecordsTest (6)

源码：[app/src/androidTest/java/com/local/douyinsaver/DynamicAlbumRecordsTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/DynamicAlbumRecordsTest.kt)

- `mixedAlbumPairsAndIndividualMimeTypesSurviveNewRecordsInstance`（源码行 24；仅源码登记）
- `malformedExternalAssetsCannotAddUrisOrClipsToExistingHistory`（源码行 37；仅源码登记）
- `legacyRecordsRemainReadableWithoutInventingMotion`（源码行 50；仅源码登记）
- `gifRangeAndConversionModePersistWithoutReplacingOriginalRecord`（源码行 62；仅源码登记）
- `embeddedPhotoAndAlbumTimingSignatureSurviveReloadWithoutChangingLegacyRecords`（源码行 72；仅源码登记）
- `engineReusesEmbeddedCleanPhotosAndRejectsLegacyCoversAndSidecars`（源码行 85；仅源码登记）

### DynamicAlbumSaveTest (8)

源码：[app/src/androidTest/java/com/local/douyinsaver/DynamicAlbumSaveTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/DynamicAlbumSaveTest.kt)

条件边界：存在assume条件调用（源码行 215, 222），须另核实opt-in/实际执行条件。

- `detectsActualGifWebpAndApngAnimationWithoutTrustingSuffix`（源码行 33；仅源码登记）
- `savesGifAndWebpBytesAndMimeTypesToMediaStore`（源码行 50；仅源码登记）
- `preservesMixedImageOrderAndSingleEmbeddedLivePhoto`（源码行 73；仅源码登记）
- `cancellingLiveVideoCopyRollsBackCoverAndVideoTogether`（源码行 117；仅源码登记）
- `fullDownloadKeepsAnimationAndRetriesOnlyMatchingMotionSources`（源码行 137；仅源码登记）
- `missingLiveSidecarOrStaticSubstituteFailsBeforePublishing`（源码行 186；仅源码登记）
- `rejectsNonMp4DynamicResourceBeforePublishing`（源码行 203；仅源码登记）
- `preservesGifAndLivePairInExplicitIsolatedSafFolder`（源码行 213；仅源码登记）

### EmbeddedLiveAlbumDownloadTest (6)

源码：[app/src/androidTest/java/com/local/douyinsaver/EmbeddedLiveAlbumDownloadTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/EmbeddedLiveAlbumDownloadTest.kt)

- `livePhotosDownloadPreservesAnEmbeddedJpegTaggedLiveWithoutAnExternalMotionUrl`（源码行 39；仅源码登记）
- `imagesDownloadPreservesAnEmbeddedJpegTaggedLiveWithoutAnExternalMotionUrl`（源码行 42；仅源码登记）
- `staticJpegTaggedLiveRejectsTheWholeAlbumBeforePublishingOrRecording`（源码行 45；仅源码登记）
- `declaredEmbeddedMp4WithInvalidVideoTracksIsRejectedBeforePublishing`（源码行 68；仅源码登记）
- `explicitCoversModeStillAllowsAStaticCoverAndDiscardsEmbeddedMotionOnlyOnRequest`（源码行 98；仅源码登记）
- `cancellingTheEmbeddedLivePhotoPublicationRollsBackItsPendingUriAndDoesNotRecordIt`（源码行 127；仅源码登记）

### EmbeddedLiveDerivedExportTest (2)

源码：[app/src/androidTest/java/com/local/douyinsaver/EmbeddedLiveDerivedExportTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/EmbeddedLiveDerivedExportTest.kt)

条件边界：存在assume条件调用（源码行 189），须另核实opt-in/实际执行条件。

- `embeddedLiveWithoutMotionUrlExportsItsCompleteTwoSecondMotionAsGif`（源码行 49；仅源码登记）
- `mixedStaticAndEmbeddedLiveUsesOriginalMotionDurationAndExplicitSeparateBgm`（源码行 64；仅源码登记）

### EmulatorVideoSourceTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/EmulatorVideoSourceTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/EmulatorVideoSourceTest.kt)

条件边界：存在assume条件调用（源码行 27），须另核实opt-in/实际执行条件。

- `captureExactWorkBeforeProbingSoARejectedCdnRemainsDiagnosable`（源码行 25；仅源码登记）

### FeatureRuntimeTest (3)

源码：[app/src/androidTest/java/com/local/douyinsaver/FeatureRuntimeTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/FeatureRuntimeTest.kt)

- `appearanceSlidersDragPersistWithoutSamplePreviewOrUserChanges`（源码行 55；仅源码登记）
- `oldVideoRecordsAndNewAlbumsSurviveRoundTripWithoutTouchingUserData`（源码行 409；仅源码登记）
- `foregroundServiceSurvivesLeavingActivityAndStopsCleanly`（源码行 439；仅源码登记）

### FourKindAlbumPipelineTest (2)

源码：[app/src/androidTest/java/com/local/douyinsaver/FourKindAlbumPipelineTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/FourKindAlbumPipelineTest.kt)

- `fourSourceKindsKeepIdentityKnownDefaultsAndEverySingleItemIsIsolated`（源码行 34；仅源码登记）
- `fourSourceKindsComposeTheirActualFramesAndSameWorkBgmInOriginalOrder`（源码行 75；仅源码登记）

### GalleryControlsUiTest (12)

源码：[app/src/androidTest/java/com/local/douyinsaver/GalleryControlsUiTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/GalleryControlsUiTest.kt)

- `staticGalleryHasNoDynamicFormatPanelAndRequiresTwoPicturesForSequenceGif`（源码行 50；仅源码登记）
- `nativeAnimationAndKnownLiveHaveDifferentDefaultActionsAndLiveCanComposeBgm`（源码行 79；仅源码登记）
- `automaticReadHoldsStaticCompositionUntilTheDynamicResultArrives`（源码行 124；仅源码登记）
- `unknownMotionNeverDefaultsToLiveAndRequiresAnExplicitFormatChoice`（源码行 150；仅源码登记）
- `unknownGeneratedLiveCoverRequiresAnExplicitClickAndWaitsForMotionReadingOrVerification`（源码行 181；仅源码登记）
- `invalidCompositionDurationDoesNotDisableOriginalPicturesAndDynamicFormats`（源码行 210；仅源码登记）
- `previewPagingPreservesOriginalIndicesAndUnknownItemAsksForFormat`（源码行 275；仅源码登记）
- `tenthsInputAndRoundSliderStaySynchronizedAndResetToAutomatic`（源码行 313；仅源码登记）
- `taskDurationEditorsDoNotChangeAnotherTaskOrSingleResult`（源码行 335；仅源码登记）
- `measuredDurationWarningRequiresContinueOrReturnToEditing`（源码行 373；仅源码登记）
- `bulkUnknownFormatIsExplicitAndOnlyChangesUnknownItems`（源码行 399；仅源码登记）
- `videoGifFullSourceAndRemainingRangesKeepActualLengthAndTenthsStart`（源码行 439；仅源码登记）

### GalleryExportPipelineTest (15)

源码：[app/src/androidTest/java/com/local/douyinsaver/GalleryExportPipelineTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/GalleryExportPipelineTest.kt)

- `liveDownloadPublishesOneRealMotionJpegAndKeepsOriginalClipIncludingAudio`（源码行 39；仅源码登记）
- `explicitAnimationConversionPublishesOneSilentMotionPhotoWithRealChangingFrames`（源码行 65；仅源码登记）
- `alreadyEmbeddedLivePhotoIsDetectedAndPublishedWithEveryOriginalByte`（源码行 90；仅源码登记）
- `animatedMp4SaveIsSilentAndPreservesEncodedVideoFramesSizeAndTiming`（源码行 114；仅源码登记）
- `unknownDynamicRequiresAnExplicitOriginalExportFormatWithoutPublishingAStillFallback`（源码行 135；仅源码登记）
- `explicitUnknownFormatChoicesNeverReclassifyKnownLiveOrAnimatedNeighbors`（源码行 153；仅源码登记）
- `unknownDynamicCanExplicitlyChooseGifWithoutPretendingItsMp4IsALivePhoto`（源码行 194；仅源码登记）
- `staticGalleryCreatesOneLoopingGifWithTwoDifferentFramesAtPointOneSecondEach`（源码行 212；仅源码登记）
- `dynamicGifCompatibilityActuallySavesAnimatedImageWithChangingDecodedFrames`（源码行 233；仅源码登记）
- `dynamicBgmCompositionUsesRealMotionAndItsDefaultDurationInsteadOfBlueStaticCover`（源码行 250；仅源码登记）
- `customDurationWarnsThenTrimsOrLoopsRealMotionWithoutChangingSpeed`（源码行 268；仅源码登记）
- `decliningDurationWarningCreatesNoPublishedFilesRecordsOrBgmRequest`（源码行 296；仅源码登记）
- `mixedSaveKeepsStaticLiveAnimatedOrderAndSingleItemDoesNotFetchNeighbors`（源码行 316；仅源码登记）
- `mixedBgmCompositionKeepsStaticThenActualMotionThenStaticWithDefaultTimes`（源码行 342；仅源码登记）
- `persistedDecimalStaticDefaultReachesRealGifAndBgmFilesWhileMotionKeepsItsDuration`（源码行 361；仅源码登记）

### GifDownloadPipelineTest (3)

源码：[app/src/androidTest/java/com/local/douyinsaver/GifDownloadPipelineTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/GifDownloadPipelineTest.kt)

- `videoRetriesCleanSourceExportsActualFramesAndKeepsPublicationAfterRecordFailure`（源码行 37；仅源码登记）
- `mixedGalleryConvertsLiveClipToGifAndPreservesStaticPngOrder`（源码行 96；仅源码登记）
- `invalidLiveMotionFailsBeforePublicationAndLeavesNoRecordOrOwnedFile`（源码行 141；仅源码登记）

### GifQualityRealSourceTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/GifQualityRealSourceTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/GifQualityRealSourceTest.kt)

条件边界：存在assume条件调用（源码行 23），须另核实opt-in/实际执行条件。

- `compareActualConfirmedMotionSourceWithoutClippingItsRange`（源码行 21；仅源码登记）

### HeadlessDesktopAlbumSourceTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/HeadlessDesktopAlbumSourceTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/HeadlessDesktopAlbumSourceTest.kt)

条件边界：存在assume条件调用（源码行 70），须另核实opt-in/实际执行条件。

- `explicitSameWorkDesktopCandidateHasRealOwnedMotionWithoutAnyWindow`（源码行 68；仅源码登记）

### HistoryControlsUiTest (6)

源码：[app/src/androidTest/java/com/local/douyinsaver/HistoryControlsUiTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/HistoryControlsUiTest.kt)

- `searchFindsTitleFileNameAndWorkIdWithoutChangingHistory`（源码行 40；仅源码登记）
- `everySortControlChangesActualCardOrderWithoutRewritingRecords`（源码行 63；仅源码登记）
- `cancellingRecordRemovalAndFileDeletionPreservesSelectionFilesAndRecords`（源码行 80；仅源码登记）
- `removingOneSelectedRecordLeavesItsFileAndAllOtherRecordsReadable`（源码行 114；仅源码登记）
- `selectAllOnlyRemovesFilteredRecordsAndIndividualCheckboxUpdatesItsState`（源码行 139；仅源码登记）
- `cancellingThenConfirmingWholeAlbumDeletionRemovesOnlyItsOwnedMediaStoreUris`（源码行 174；仅源码登记）

### HomeInputStateTest (7)

源码：[app/src/androidTest/java/com/local/douyinsaver/HomeInputStateTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/HomeInputStateTest.kt)

- `editingReadyInputInvalidatesItsResultAndPreservesOtherTasksAndHistory`（源码行 25；仅源码登记）
- `clearingPersistsAnEmptyInputAndStaleParserCallbacksCannotRestoreTheOldResult`（源码行 47；仅源码登记）
- `busyInputActionsAreIgnoredAndBatchAdditionDoesNotOverwriteTaskProgress`（源码行 70；仅源码登记）
- `aSingleResolvePreservesTheShareTextAndDoesNotCreateBatchQueueRows`（源码行 93；仅源码登记）
- `resolvingAgainPreservesThePreviousIndependentQueueResultWithoutAddingAnotherRow`（源码行 108；仅源码登记）
- `addingBatchLinksPreservesReadyInputAndRemovingItsActiveRowClearsOnlyThatResult`（源码行 128；仅源码登记）
- `startingBatchQueueKeepsTheSingleInputAndRestoresItsReadyResult`（源码行 151；仅源码登记）

### HomeInteractionTest (2)

源码：[app/src/androidTest/java/com/local/douyinsaver/HomeInteractionTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/HomeInteractionTest.kt)

- `clearControlRemovesReadyAlbumAndReturnsToSinglePasteAction`（源码行 24；仅源码登记）
- `onePrimaryButtonAdaptsToInputWithoutHomeSaveLocation`（源码行 48；仅源码登记）

### MotionPhotoExporterTest (5)

源码：[app/src/androidTest/java/com/local/douyinsaver/MotionPhotoExporterTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/MotionPhotoExporterTest.kt)

- `singleJpegPreservesFullCoverAndOriginalVideoAudioSamplesAndDecodesChangingFrames`（源码行 27；仅源码登记）
- `mediaStorePublishesOneImageAndExtractsItsRealEmbeddedVideoWithoutASeparateVideoRow`（源码行 59；仅源码登记）
- `invalidMotionOrCoverTimestampDoesNotPublishOrLoseSourceFiles`（源码行 83；仅源码登记）
- `explicitAnimationConversionHasMatchingFullSizeCoverKnownTimestampAndSilentOriginalMotion`（源码行 100；仅源码登记）
- `galleryProfileMatchesActualMidClipCoverAndRejectsUnrelatedCoverWithoutPublishing`（源码行 127；仅源码登记）

### NativeAnimationPublishTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/NativeAnimationPublishTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/NativeAnimationPublishTest.kt)

- `publishesOriginalApngBytesAndChangingFramesWithPngMimeAndReloadableAnimatedRecord`（源码行 23；仅源码登记）

### NativeLiveAppPipelineTest (3)

源码：[app/src/androidTest/java/com/local/douyinsaver/NativeLiveAppPipelineTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/NativeLiveAppPipelineTest.kt)

条件边界：存在assume条件调用（源码行 360, 361），须另核实opt-in/实际执行条件。

- `primaryLiveButtonVerifiesAndPreservesCompleteNativeJpegWithoutSeparateMotionUrl`（源码行 63；仅源码登记）
- `individualLivePreviewSavesOnlySelectedNativeImageAndRetainsOriginalIndex`（源码行 95；仅源码登记）
- `liveQueueCardSavesNativeFileAndBatchRejectsStillCoverThenContinuesNeighbor`（源码行 119；仅源码登记）

### OfficialWorkDataShapeDiagnosticTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/OfficialWorkDataShapeDiagnosticTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/OfficialWorkDataShapeDiagnosticTest.kt)

条件边界：存在assume条件调用（源码行 66），须另核实opt-in/实际执行条件。

- `inspectOnlyTheSafeShapeOfOneExplicitOfficialWorkOnMobileAndDesktop`（源码行 63；仅源码登记）

### OriginalMotionJpegRealSourceTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/OriginalMotionJpegRealSourceTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/OriginalMotionJpegRealSourceTest.kt)

条件边界：存在assume条件调用（源码行 41），须另核实opt-in/实际执行条件。

- `userSuppliedEmbeddedMotionJpegRetainsAllMediaThroughContentDownloader`（源码行 39；仅源码登记）

### PhonePipelineTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/PhonePipelineTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/PhonePipelineTest.kt)

条件边界：存在assume条件调用（源码行 45），须另核实opt-in/实际执行条件。

- `currentFailedSample`（源码行 42；仅源码登记）

### PhoneSourceValidationTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/PhoneSourceValidationTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/PhoneSourceValidationTest.kt)

条件边界：存在assume条件调用（源码行 58），须另核实opt-in/实际执行条件。

- `validateOneExplicitOfficialShareThroughAnIsolatedHeadlessEngine`（源码行 56；仅源码登记）

### PreviewInteractionTest (5)

源码：[app/src/androidTest/java/com/local/douyinsaver/PreviewInteractionTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/PreviewInteractionTest.kt)

- `decoderInventoryForPreview`（源码行 72；仅源码登记）
- `localPreviewPlaysPausesSeeksMutesReplaysAndReleasesOnDismiss`（源码行 84；仅源码登记）
- `localPreviewPausesForBackgroundAndReleasesWithActivity`（源码行 178；仅源码登记）
- `previewSettingsPersistIndependentStepsAndSeekActualPlayer`（源码行 235；仅源码登记）
- `previewShortcutsFitOneRowAndRevealCompleteLastButtonWithLargeText`（源码行 317；仅源码登记）

### PublicGifPipelineTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/PublicGifPipelineTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/PublicGifPipelineTest.kt)

条件边界：存在assume条件调用（源码行 51），须另核实opt-in/实际执行条件。

- `explicitlyRequestedPublicVideoParsesDownloadsAndExportsAnimatedGif`（源码行 48；仅源码登记）

### QueueEngineTest (26)

源码：[app/src/androidTest/java/com/local/douyinsaver/QueueEngineTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/QueueEngineTest.kt)

- `explicitBatchFormatAppliesOnlyToUnknownDynamicAndPreservesNeighborDefaults`（源码行 47；仅源码登记）
- `singleDisplayOnlyAlbumBecomesReadyWithEveryImageAndItsMusic`（源码行 76；仅源码登记）
- `automaticQueueAcceptsDisplayOnlyAlbumsAndContinuesPastAnIncompleteAlbum`（源码行 104；仅源码登记）
- `oneStartParsesEveryVideoAndAlbumWithoutStartingAnySave`（源码行 161；仅源码登记）
- `automaticMixedDynamicQueueUsesCleanImagesAndKeepsLiveManifestForRepeatSaves`（源码行 195；仅源码登记）
- `aFailedItemDoesNotStopLaterAlbumsAndCanBeRetriedIndividually`（源码行 279；仅源码登记）
- `cancellingAndRestartingIgnoresStaleCallbacksAndKeepsEarlierResults`（源码行 304；仅源码登记）
- `runningQueueLocksSortingAndActiveDeletionButCanRemoveAWaitingItem`（源码行 339；仅源码登记）
- `explicitBatchSaveContinuesAfterFailureAndStoresAnAlbumWithoutBgmAsImages`（源码行 360；仅源码登记）
- `batchParsingPreservesAndRestoresTheIndependentSingleLinkResult`（源码行 398；仅源码登记）
- `individualRetryAndSaveSuccessOrFailureRestoreTheSingleResult`（源码行 428；仅源码登记）
- `queueAndBatchGapsLockNamingFolderAndHistoryOperations`（源码行 467；仅源码登记）
- `cancellingBatchInAnIdleGapKeepsSavedFilesAndRestoresTheSingleResult`（源码行 500；仅源码登记）
- `differentShortLinksForTheSameWorkSaveOnlyOneFileInTheBatch`（源码行 539；仅源码登记）
- `cancellingAnIndividualRetryRestoresTheSingleResultOrClearsAnEmptySelection`（源码行 571；仅源码登记）
- `clearingAllStatusesPersistsAnEmptyQueueAndPreservesTheIndependentResultAndHistory`（源码行 610；仅源码登记）
- `clearingIsBlockedDuringBusyWorkAndBothIdleProcessingGaps`（源码行 676；仅源码登记）
- `onlyConfirmedCleanHistoryCountsAsAnExistingDownload`（源码行 715；仅源码登记）
- `obsoleteVersionChangesCannotAlterSingleOrBatchSavingAndRepeatedBatchIsSkipped`（源码行 734；仅源码登记）
- `singleSavingUsesTheCleanSourceAndDoesNotSkipAnOlderMarkedRecord`（源码行 798；仅源码登记）
- `obsoleteVersionSettersDoNotWritePreferencesAndNewInputAlwaysUsesClean`（源码行 830；仅源码登记）
- `legacyMarkedAndOriginalDefaultsAreIgnoredWithoutDeletingPreferencesOrRecords`（源码行 855；仅源码登记）
- `missingCleanAlbumImagesAndUnclassifiedVideoFailWithoutBlockingLaterWorks`（源码行 876；仅源码登记）
- `removingHistoryRefreshesQueueAndSingleSnapshotAndNeverSkipsADeletedFile`（源码行 910；仅源码登记）
- `refreshingHistoryDropsUnreadableQueueFilesThatWereDeletedOutsideTheApp`（源码行 959；仅源码登记）
- `rejectedProbeHostSurvivesEngineDiagnosticsWhileSecretsNeverReachTheSavedSummary`（源码行 987；仅源码登记）

### SilentVideoRemuxerOwnershipTest (3)

源码：[app/src/androidTest/java/com/local/douyinsaver/SilentVideoRemuxerOwnershipTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/SilentVideoRemuxerOwnershipTest.kt)

- `guardsSourceAndPreexistingDestinationWithoutDeletingOrChangingBytes`（源码行 24；仅源码登记）
- `preservesEveryCompressedSampleAndExactVideoTailWhileRemovingAudio`（源码行 44；仅源码登记）
- `preservesUnequalFinalFrameDurationAndRotationWithoutFollowingLongerAudio`（源码行 58；仅源码登记）

### StaticAlbumGifAppPipelineTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/StaticAlbumGifAppPipelineTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/StaticAlbumGifAppPipelineTest.kt)

条件边界：存在assume条件调用（源码行 57, 62），须另核实opt-in/实际执行条件。

- `publicStaticAlbumUsesActualPointOneSecondInputAndPublishesOneOrderedLoopingGif`（源码行 55；仅源码登记）

### StaticImageTimingSettingsTest (3)

源码：[app/src/androidTest/java/com/local/douyinsaver/StaticImageTimingSettingsTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/StaticImageTimingSettingsTest.kt)

- `newInstallStartsAtThreeSecondsAndLegacyChoiceSurvivesMigration`（源码行 23；仅源码登记）
- `tenthsPersistWithoutChangingLegacyPreferencesOrOldHistoryAndRejectInvalidUpdates`（源码行 34；仅源码登记）
- `productionEnginePassesDecimalDefaultToEverySaveModeWhileKeepingTheOverrideIndependent`（源码行 57；仅源码登记）

### StaticImageTimingUiTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/StaticImageTimingUiTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/StaticImageTimingUiTest.kt)

- `decimalInputRoundSliderInvalidRecoveryAndFreshEngineRestoreUseOnlyIsolatedPreferences`（源码行 42；仅源码登记）

### StorageDirectoryProbeTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/StorageDirectoryProbeTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/StorageDirectoryProbeTest.kt)

条件边界：存在assume条件调用（源码行 24），须另核实opt-in/实际执行条件。

- `publishAndReadOneOwnedFileAtAnExplicitlyChosenSupportedDirectory`（源码行 21；仅源码登记）

### VideoGifConverterTest (11)

源码：[app/src/androidTest/java/com/local/douyinsaver/VideoGifConverterTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/VideoGifConverterTest.kt)

- `gifDefaultDurationFollowsVideoTrackWhenOriginalAudioIsTwiceAsLong`（源码行 36；仅源码登记）
- `convertsRealVideoToLoopingGifAndDecodesRedAndBlueFrames`（源码行 107；仅源码登记）
- `shareAndHighQualityDecodeTheSameCompleteTwoSecondVideo`（源码行 156；仅源码登记）
- `shareModeDecodesTheCompleteLongVideoIncludingItsRealTailTransition`（源码行 204；仅源码登记）
- `respectsSelectedRangeAndRotationWithoutStretchingFrames`（源码行 261；仅源码登记）
- `cancellationRemovesOnlyNewGifAndLeavesOriginalVideoUnchanged`（源码行 288；仅源码登记）
- `exportsOneTenthSecondAndWholeVideoLongerThanFifteenSeconds`（源码行 308；仅源码登记）
- `exportsFromMiddleToRealEndAndFinalTenthSecondWithTheActualTailFrames`（源码行 381；仅源码登记）
- `galleryOverridesTrimBeginningOrLoopTheActualVideoFrames`（源码行 452；仅源码登记）
- `neverOverwritesExistingDestinationOrSourceAndCleansInvalidInput`（源码行 484；仅源码登记）
- `rejectsOneRealVideoSampleDespiteUsableDurationAndPreservesAllInputs`（源码行 502；仅源码登记）

### WatermarkInteractionTest (3)

源码：[app/src/androidTest/java/com/local/douyinsaver/WatermarkInteractionTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/WatermarkInteractionTest.kt)

- `cleanSourceUsesOneOrdinaryDownloadButtonAndIgnoresLegacyVersionPreferences`（源码行 34；仅源码登记）
- `aMarkedOnlySourceCannotEnableDownloadOrOfferAFallback`（源码行 57；仅源码登记）
- `anUnclassifiedPlayableSourceCannotEnableDownloadOrOfferAnOriginalFallback`（源码行 69；仅源码登记）

### WatermarkRecordsTest (2)

源码：[app/src/androidTest/java/com/local/douyinsaver/WatermarkRecordsTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/WatermarkRecordsTest.kt)

- `existingClassificationsAndCleanRecordsRoundTripWithoutRewritingHistory`（源码行 17；仅源码登记）
- `duplicateDetectionAlwaysUsesCleanAndNeverReusesMarkedOrUnknownRecords`（源码行 32；仅源码登记）

### WebViewConnectivityTest (1)

源码：[app/src/androidTest/java/com/local/douyinsaver/WebViewConnectivityTest.kt](../../app/src/androidTest/java/com/local/douyinsaver/WebViewConnectivityTest.kt)

条件边界：存在assume条件调用（源码行 45），须另核实opt-in/实际执行条件。

- `compareNativeHttpsAndAnOrdinaryAttachedWebView`（源码行 42；仅源码登记）
