/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara;


import static com.google.copybara.GeneralOptions.FORCE;
import static com.google.copybara.Origin.Reader.ChangesResponse.EmptyReason.NO_CHANGES;
import static com.google.copybara.WorkflowOptions.CHANGE_REQUEST_FROM_SOT_LIMIT_FLAG;
import static com.google.copybara.WorkflowOptions.CHANGE_REQUEST_PARENT_FLAG;
import static com.google.copybara.exception.ValidationException.checkCondition;
import static com.google.copybara.exception.ValidationException.retriableException;
import static java.lang.String.format;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Iterables;
import com.google.common.flogger.FluentLogger;
import com.google.copybara.ChangeVisitable.VisitResult;
import com.google.copybara.Origin.Baseline;
import com.google.copybara.Origin.Reader.ChangesResponse;
import com.google.copybara.Origin.Reader.ChangesResponse.EmptyReason;
import com.google.copybara.WorkflowRunHelper.ChangeMigrator;
import com.google.copybara.doc.annotations.DocField;
import com.google.copybara.effect.DestinationEffect;
import com.google.copybara.effect.DestinationEffect.Type;
import com.google.copybara.exception.CannotResolveRevisionException;
import com.google.copybara.exception.ChangeRejectedException;
import com.google.copybara.exception.EmptyChangeException;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.profiler.Profiler.ProfilerTask;
import com.google.copybara.revision.Change;
import com.google.copybara.revision.Changes;
import com.google.copybara.revision.Revision;
import com.google.copybara.util.console.PrefixConsole;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Workflow type to run between origin an destination
 */
public enum WorkflowMode {
  /**
   * Create a single commit in the destination with new tree state.
   */
  @DocField(description = "Create a single commit in the destination with new tree state.")
  SQUASH {
    @Override
    <O extends Revision, D extends Revision> void run(WorkflowRunHelper<O, D> runHelper)
        throws RepoException, IOException, ValidationException {
      ImmutableList<Change<O>> detectedChanges = ImmutableList.of();
      ImmutableMap<Change<O>, Change<O>> conditionalChanges = ImmutableMap.of();
      O current = runHelper.getResolvedRef();
      O lastRev = null;
      if (isHistorySupported(runHelper)) {
        lastRev = maybeGetLastRev(runHelper);
        if (runHelper.workflowOptions().importSameVersion) {
          current = lastRev; // Import the last imported version.
        } else {
          ChangesResponse<O> response = runHelper.getChanges(lastRev, current);
          if (response.isEmpty()) {
            manageNoChangesDetectedForSquash(
                runHelper, current, lastRev, response.getEmptyReason());
          } else {
            detectedChanges = response.getChanges();
            conditionalChanges = response.getConditionalChanges();
          }
        }
      }

      Metadata metadata = new Metadata(
          runHelper.getChangeMessage("Project import generated by Copybara.\n"),
          // SQUASH workflows always use the default author if it was not forced.
          runHelper.getFinalAuthor(runHelper.getAuthoring().getDefaultAuthor()),
          ImmutableSetMultimap.of());

      runHelper.maybeValidateRepoInLastRevState(metadata);

      // Don't replace helperForChanges with runHelper since origin_files could
      // be potentially different in the helper for the current change.
      ChangeMigrator<O, D> helperForChanges = detectedChanges.isEmpty()
          ? runHelper.getDefaultMigrator()
          : runHelper.getMigratorForChange(Iterables.getLast(detectedChanges));

      // Remove changes that don't affect origin_files
      ImmutableList<Change<O>> changes = filterChanges(
          detectedChanges, conditionalChanges, helperForChanges);
      if (changes.isEmpty()
          && isHistorySupported(runHelper)
          && !runHelper.workflowOptions().importSameVersion) {
        manageNoChangesDetectedForSquash(runHelper, current, lastRev, NO_CHANGES);
      }

      // Try to use the latest change that affected the origin_files roots instead of the
      // current revision, that could be an unrelated change.
      current = changes.isEmpty()
          ? current
          : Iterables.getLast(changes).getRevision();

      if (runHelper.isSquashWithoutHistory()) {
        changes = ImmutableList.of();
      }
      checkCondition(
          current != null,
          "Could not process ref. If using --same-version flag, please either (1) check our"
              + " METADATA file to confirm version is properly formatted or (2) also use the"
              + " --last-rev flag to manually specify");
      helperForChanges.migrate(
          current,
          lastRev,
          runHelper.getConsole(),
          metadata,
          // Squash notes an Skylark API expect last commit to be the first one.
          new Changes(changes.reverse(), ImmutableList.of()),
          /*destinationBaseline=*/ null,
          runHelper.getResolvedRef(),
          lastRev);
    }
  },

  /** Import each origin change individually. */
  @DocField(description = "Import each origin change individually.")
  ITERATIVE {
    @Override
    <O extends Revision, D extends Revision> void run(WorkflowRunHelper<O, D> runHelper)
        throws RepoException, IOException, ValidationException {
      O lastRev = runHelper.getLastRev();
      ChangesResponse<O> changesResponse =
          runHelper.getChanges(lastRev, runHelper.getResolvedRef());
      if (changesResponse.isEmpty()) {
        ValidationException.checkCondition(
            !changesResponse.getEmptyReason().equals(EmptyReason.UNRELATED_REVISIONS),
            "last imported revision %s is not ancestor of requested revision %s",
            lastRev, runHelper.getResolvedRef());
        throw new EmptyChangeException(
            "No new changes to import for resolved ref: " + runHelper.getResolvedRef().asString());
      }
      int changeNumber = 1;

      ImmutableList<Change<O>> changes = ImmutableList.copyOf(changesResponse.getChanges());
      Iterator<Change<O>> changesIterator = changes.iterator();
      int limit = changes.size();
      if (runHelper.workflowOptions().iterativeLimitChanges < changes.size()) {
        runHelper.getConsole().info(String.format("Importing first %d change(s) out of %d",
            limit, changes.size()));
        limit = runHelper.workflowOptions().iterativeLimitChanges;
      }

      runHelper.maybeValidateRepoInLastRevState(/*metadata=*/null);

      Deque<Change<O>> migrated = new ArrayDeque<>();
      int migratedChanges = 0;
      while (changesIterator.hasNext() && migratedChanges < limit) {
        Change<O> change = changesIterator.next();
        String prefix = String.format(
            "Change %d of %d (%s): ",
            changeNumber, Math.min(changes.size(), limit), change.getRevision().asString());
        ImmutableList<DestinationEffect> result;

        boolean errors = false;
        try (ProfilerTask ignored = runHelper.profiler().start(change.getRef())) {
          ImmutableList<Change<O>> current = ImmutableList.of(change);
          ChangeMigrator<O, D> migrator = runHelper.getMigratorForChange(change);
          if (migrator.skipChange(change)) {
            continue;
          }
          result =
              migrator.migrate(
                  change.getRevision(),
                  lastRev,
                  new PrefixConsole(prefix, runHelper.getConsole()),
                  new Metadata(
                      runHelper.getChangeMessage(change.getMessage()),
                      runHelper.getFinalAuthor(change.getAuthor()),
                      ImmutableSetMultimap.of()),
                  new Changes(current, migrated),
                  /*destinationBaseline=*/ null,
                  // Use the current change since we might want to create different
                  // reviews in the destination. Will not work if we want to group
                  // all the changes in the same Github PR
                  change.getRevision(),
                  null);
          migratedChanges++;
          for (DestinationEffect effect : result) {
            if (effect.getType() != Type.NOOP) {
              errors |= !effect.getErrors().isEmpty();
            }
          }
        } catch (EmptyChangeException e) {
          runHelper.getConsole().warnFmt("Migration of origin revision '%s' resulted in an empty"
              + " change in the destination: %s", change.getRevision().asString(), e.getMessage());
        } catch (ValidationException | RepoException e) {
          runHelper.getConsole().errorFmt("Migration of origin revision '%s' failed with error: %s",
              change.getRevision().asString(), e.getMessage());
          throw e;
        }
        migrated.addFirst(change);

        if (errors && changesIterator.hasNext()) {
          // Use the regular console to log prompt and final message, it will be easier to spot
          if (!runHelper.getConsole()
              .promptConfirmation("Continue importing next change?")) {
            String message = String.format("Iterative workflow aborted by user after: %s", prefix);
            runHelper.getConsole().warn(message);
            throw new ChangeRejectedException(message);
          }
        }
        changeNumber++;
      }
      if (migratedChanges == 0) {
        throw new EmptyChangeException(
            String.format(
                "Iterative workflow produced no changes in the destination for resolved ref: %s",
                runHelper.getResolvedRef().asString()));
      }
      logger.atInfo().log("Imported %d change(s) out of %d", migratedChanges, changes.size());
    }
  },
  @DocField(description = "Import an origin tree state diffed by a common parent"
      + " in destination. This could be a GH Pull Request, a Gerrit Change, etc.")
  CHANGE_REQUEST {
    
    @Override
    <O extends Revision, D extends Revision> void run(WorkflowRunHelper<O, D> runHelper)
        throws RepoException, IOException, ValidationException {

      checkCondition(runHelper.destinationSupportsPreviousRef(),
          "'%s' is incompatible with destinations that don't support history"
              + " (For example folder.destination)", CHANGE_REQUEST);
      String originLabelName = runHelper.getLabelNameWhenOrigin();
      Optional<Baseline<O>> baseline;
      /*originRevision=*/
      baseline = Strings.isNullOrEmpty(runHelper.workflowOptions().changeBaseline)
          ? runHelper.getOriginReader().findBaseline(runHelper.getResolvedRef(), originLabelName)
          : Optional.of(
              new Baseline<O>(runHelper.workflowOptions().changeBaseline, /*originRevision=*/null));

      runChangeRequest(
          runHelper,
          baseline,
          runHelper.workflowOptions().baselineForMergeImport == null
              ? baseline
                  .orElseThrow(() ->
                      new ValidationException(
                          "Cannot read origin revision from baseline, please specify the baseline"
                              + " using the --baseline-for-merge-import flag."))
                  .getOriginRevision()
              : runHelper.originResolveLastRev(runHelper.workflowOptions().baselineForMergeImport));
    }},
    @DocField(
        description = "Import **from** the Source-of-Truth. This mode is useful when, despite the"
            + " pending change being already in the SoT, the users want to review the code on a"
            + " different system."
    )
    CHANGE_REQUEST_FROM_SOT {
    @Override
    <O extends Revision, D extends Revision> void run(WorkflowRunHelper<O, D> runHelper)
        throws RepoException, IOException, ValidationException {

      ImmutableList<O> originBaselines;
      if (Strings.isNullOrEmpty(runHelper.workflowOptions().changeBaseline)) {
        originBaselines = runHelper
            .getOriginReader()
            .findBaselinesWithoutLabel(runHelper.getResolvedRef(),
                runHelper.workflowOptions().changeRequestFromSotLimit);
      } else {
        originBaselines = ImmutableList.of(
            runHelper.originResolveLastRev(runHelper.workflowOptions().changeBaseline));
      }

      Baseline<O> destinationBaseline = getDestinationBaseline(runHelper, originBaselines);

      if (destinationBaseline == null) {
        checkCondition(!originBaselines.isEmpty(),
            "Couldn't find any parent change for %s and origin_files = %s",
            runHelper.getResolvedRef().asString(), runHelper.getOriginFiles());

        throw retriableException(format(
            "Couldn't find a change in the destination with %s label that matches a change from"
                + " the origin. Make sure"
                + " to sync the submitted changes from the origin -> destination first or use"
                + " SQUASH mode or use %s",
            runHelper.getOriginLabelName(),
            CHANGE_REQUEST_FROM_SOT_LIMIT_FLAG));
      }
      runChangeRequest(runHelper, Optional.of(destinationBaseline), null);
    }

    @Nullable
    private <O extends Revision, D extends Revision> Baseline<O>
    getDestinationBaseline(WorkflowRunHelper<O, D> runHelper, ImmutableList<O> originRevision)
        throws RepoException, ValidationException {

      Baseline<O> result =
          getDestinationBaselineOneAttempt(runHelper, originRevision);
      if (result != null) {
        return result;
      }

      for (Integer delay : runHelper.workflowOptions().changeRequestFromSotRetry) {
        runHelper.getConsole().warnFmt(
            "Couldn't find a change in the destination with %s label and %s value."
                + " Retrying in %s seconds...",
            runHelper.getOriginLabelName(), originRevision, delay);
        try {
          TimeUnit.SECONDS.sleep(delay);
        } catch (InterruptedException e) {
          throw new RepoException("Interrupted while waiting for CHANGE_REQUEST_FROM_SOT"
              + " destination baseline to be available", e);
        }
        result = getDestinationBaselineOneAttempt(runHelper, originRevision);
        if (result != null) {
          return result;
        }
      }
      return null;
    }

    @Nullable
    private <O extends Revision, D extends Revision> Baseline<O>
    getDestinationBaselineOneAttempt(
        WorkflowRunHelper<O, D> runHelper, ImmutableList<O> originRevisions)
        throws RepoException, ValidationException {

      @SuppressWarnings({"unchecked", "rawtypes"})
      Baseline<O>[] result = new Baseline[] {null};
      runHelper
          .getDestinationWriter()
          .visitChangesWithAnyLabel(
              /* start= */ null,
              ImmutableList.of(runHelper.getOriginLabelName()),
              (change, matchedLabels) -> {
                for (String value : matchedLabels.values()) {
                  for (O originRevision : originRevisions) {
                    if (revisionWithoutReviewInfo(originRevision.asString())
                        .equals(revisionWithoutReviewInfo(value))) {
                      result[0] = new Baseline<>(change.getRevision().asString(), originRevision);
                      runHelper.getConsole().verboseFmt("Found baseline %s", result[0]);
                      return VisitResult.TERMINATE;
                    }
                  }
                }
                return VisitResult.CONTINUE;
              });
      return result[0];
    }
  };

  /**
   * Technically revisions can contain additional metadata in the String. For example:
   * 'aaaabbbbccccddddeeeeffff1111222233334444 PatchSet-1'. This method return the identification
   * part.
   */
  private static String revisionWithoutReviewInfo(String r) {
    return r.replaceFirst(" .*", "");
  }

  private static <O extends Revision, D extends Revision> void runChangeRequest(
      WorkflowRunHelper<O, D> runHelper,
      Optional<Baseline<O>> baseline,
      @Nullable O baselineForMergeImport)
      throws ValidationException, RepoException, IOException {
    checkCondition(baseline.isPresent(),
        "Cannot find matching parent commit in the destination. Use '%s' flag to force a"
            + " parent commit to use as baseline in the destination.",
        CHANGE_REQUEST_PARENT_FLAG);
    logger.atInfo().log("Found baseline %s", baseline.get().getBaseline());

    ChangeMigrator<O, D> migrator = runHelper.getDefaultMigrator();
    // If --change_request_parent was used, we don't have information about the origin changes
    // included in the CHANGE_REQUEST so we assume the last change is the only change
    ImmutableList<Change<O>> changes;
    if (baseline.get().getOriginRevision() == null) {
      changes = ImmutableList.of(runHelper.getOriginReader().change(runHelper.getResolvedRef()));
    } else {
      ChangesResponse<O> changesResponse = runHelper.getOriginReader()
          .changes(baseline.get().getOriginRevision(),
              runHelper.getResolvedRef());
      if (changesResponse.isEmpty()) {
        throw new EmptyChangeException(
            format("Change '%s' doesn't include any change for origin_files = %s",
                runHelper.getResolvedRef(), runHelper.getOriginFiles()));
      }
      changes = filterChanges(
          changesResponse.getChanges(), changesResponse.getConditionalChanges(), migrator);
      if (changes.isEmpty()) {
        migrator.finishedMigrate(
            ImmutableList.of(
                new DestinationEffect(
                    Type.NOOP,
                    String.format("Cannot migrate revisions [%s]: %s",
                        changesResponse.getChanges().isEmpty()
                            ? "Unknown"
                            : Joiner.on(", ").join(changesResponse.getChanges().stream()
                                .map(c -> c.getRevision().asString())
                                .iterator()), "didn't affect any destination file"),
                    changesResponse.getChanges(),
                    /*destinationRef=*/ null)));
        throw new EmptyChangeException(
            format("Change '%s' doesn't include any change for origin_files = %s",
                runHelper.getResolvedRef(), runHelper.getOriginFiles()));
      }
    }

    // --read-config-from-change is not implemented in CHANGE_REQUEST mode
    migrator.migrate(
        runHelper.getResolvedRef(),
        /*lastRev=*/ null,
        runHelper.getConsole(),
        // Use latest change as the message/author. If it contains multiple changes the user
        // can always use metadata.squash_notes or similar.
        new Metadata(
            runHelper.getChangeMessage(Iterables.getLast(changes).getMessage()),
            runHelper.getFinalAuthor(Iterables.getLast(changes).getAuthor()),
            ImmutableSetMultimap.of()),
        // Squash notes an Skylark API expect last commit to be the first one.
        new Changes(changes.reverse(), ImmutableList.of()),
        baseline.get(),
        runHelper.getResolvedRef(),
        baselineForMergeImport);
  }

  private static <O extends Revision, D extends Revision> void manageNoChangesDetectedForSquash(
      WorkflowRunHelper<O, D> runHelper, O current, O lastRev, EmptyReason emptyReason)
      throws ValidationException {
    switch (emptyReason) {
      case NO_CHANGES:
        String noChangesMsg =
            String.format(
                "No changes%s up to %s match any origin_files",
                lastRev == null ? "" : " from " + lastRev.asString(), current.asString());
        if (!runHelper.isForce()) {
          throw new EmptyChangeException(
              String.format(
                  "%s. Use %s if you really want to run the migration anyway.",
                  noChangesMsg, GeneralOptions.FORCE));
        }
        runHelper
            .getConsole()
            .warnFmt("%s. Migrating anyway because of %s", noChangesMsg, GeneralOptions.FORCE);
        break;
      case TO_IS_ANCESTOR:
        if (!runHelper.isForce()) {
          throw new EmptyChangeException(
              String.format(
                  "'%s' has been already migrated. Use %s if you really want to run the migration"
                      + " again (For example if the copy.bara.sky file has changed).",
                  current.asString(), GeneralOptions.FORCE));
        }
        runHelper
            .getConsole()
            .warnFmt(
                "'%s' has been already migrated. Migrating anyway" + " because of %s",
                lastRev.asString(), GeneralOptions.FORCE);
        break;
      case UNRELATED_REVISIONS:
        checkCondition(
            runHelper.isForce(),
            String.format(
                "Last imported revision '%s' is not an ancestor of the revision currently being"
                    + " migrated ('%s'). Use %s if you really want to migrate the reference.",
                lastRev, current.asString(), GeneralOptions.FORCE));
        runHelper
            .getConsole()
            .warnFmt(
                "Last imported revision '%s' is not an ancestor of the revision currently being"
                    + " migrated ('%s')",
                lastRev, current.asString());
        break;
    }
  }

  private static boolean isHistorySupported(WorkflowRunHelper<?, ?> helper) {
    return helper.destinationSupportsPreviousRef() && helper.getOriginReader().supportsHistory();
  }

  static <O extends Revision, D extends Revision> ImmutableList<Change<O>> filterChanges(
      ImmutableList<Change<O>> detectedChanges,
      ImmutableMap<Change<O>, Change<O>> conditionalChanges,
      ChangeMigrator<O, D> changeMigrator) {

    List<Change<O>> includedChanges = detectedChanges.stream()
        .filter(e -> !changeMigrator.skipChange(e))
        .collect(Collectors.toList());

    // For all the changes that should be included based on skipChange, find the ones that
    // should be added unconditionally
    List<Change<O>> unconditionalChanges = includedChanges.stream()
        .filter(e -> !conditionalChanges.keySet().contains(e))
        .collect(Collectors.toList());

    // Only include unconditional changes or conditional changes that its dependant change is
    // included
    return includedChanges.stream()
        .filter(e -> unconditionalChanges.contains(e)
            || (conditionalChanges.containsKey(e)
            && unconditionalChanges.contains(conditionalChanges.get(e))))
    .collect(ImmutableList.toImmutableList());
  }

  /**
   * Returns the last rev if possible. If --force is not enabled it will fail if not found.
   */
  @Nullable
  private static <O extends Revision, D extends Revision> O maybeGetLastRev(
      WorkflowRunHelper<O, D> runHelper) throws RepoException, ValidationException {
    try {
      return runHelper.getLastRev();
    } catch (CannotResolveRevisionException e) {
      if (runHelper.isForce()) {
        runHelper.getConsole().warnFmt(
            "Cannot find last imported revision, but proceeding because of %s flag",
            GeneralOptions.FORCE);
      } else {
        throw new ValidationException(
            String.format("Cannot find last imported revision. Use %s if you really want to proceed"
                + " with the migration use, or use '--last-rev' to override the revision.", FORCE),
            e);
      }
      return null;
    }
  }

  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  abstract <O extends Revision, D extends Revision> void run(
      WorkflowRunHelper<O, D> runHelper) throws RepoException, IOException, ValidationException;
}
