package com.hubspot.singularity.scheduler;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.hubspot.singularity.RequestState;
import com.hubspot.singularity.SingularityDeployStatistics;
import com.hubspot.singularity.SingularityRequest;
import com.hubspot.singularity.config.SingularityConfiguration;

@Singleton
public class SingularityCooldown {

  private static final Logger LOG = LoggerFactory.getLogger(SingularityCooldown.class);

  private final SingularityConfiguration configuration;

  @Inject
  public SingularityCooldown(SingularityConfiguration configuration) {
    this.configuration = configuration;
  }

  boolean shouldEnterCooldown(SingularityRequest request, RequestState requestState, SingularityDeployStatistics deployStatistics, long failureTimestamp) {
    if (requestState != RequestState.ACTIVE || !request.isAlwaysRunning()) {
      return false;
    }

    return hasFailedTooManyTimes(deployStatistics, Optional.of(failureTimestamp));
  }

  private boolean hasFailedTooManyTimes(SingularityDeployStatistics deployStatistics, Optional<Long> recentFailureTimestamp) {
    return hasFastFailureLoop(deployStatistics, recentFailureTimestamp) || hasSlowFailureLoop(deployStatistics, recentFailureTimestamp);
  }

  private boolean hasSlowFailureLoop(SingularityDeployStatistics deployStatistics, Optional<Long> recentFailureTimestamp) {
    final long now = System.currentTimeMillis();
    long thresholdTime = now - configuration.getSlowFailureCooldownMs();
    List<Long> failureTimestamps = deployStatistics.getInstanceSequentialFailureTimestamps().asMap()
        .values()
        .stream()
        .flatMap(Collection::stream)
        .collect(Collectors.toList());
    if (recentFailureTimestamp.isPresent()) {
      failureTimestamps.add(recentFailureTimestamp.get());
    }
    long failureCount = failureTimestamps.stream()
        .filter((t) -> t > thresholdTime)
        .count();
    java.util.Optional<Long> mostRecentFailure = failureTimestamps.stream().max(Comparator.comparingLong(Long::valueOf));

    return failureCount >= configuration.getSlowFailureCooldownCount()
        && mostRecentFailure.isPresent()
        && mostRecentFailure.get() > System.currentTimeMillis() - configuration.getSlowCooldownExpiresMinutesWithoutFailure();
  }

  private boolean hasFastFailureLoop(SingularityDeployStatistics deployStatistics, Optional<Long> recentFailureTimestamp) {
    final long now = System.currentTimeMillis();
    long thresholdTime = now - configuration.getFastFailureCooldownMs();
    long failureCount = deployStatistics.getInstanceSequentialFailureTimestamps().asMap()
        .values()
        .stream()
        .flatMap(Collection::stream)
        .filter((t) -> t > thresholdTime)
        .count();
    if (recentFailureTimestamp.isPresent() && recentFailureTimestamp.get() > thresholdTime) {
      failureCount++;
    }

    return failureCount >= configuration.getFastFailureCooldownCount();
  }

  boolean hasCooldownExpired(SingularityDeployStatistics deployStatistics, Optional<Long> recentFailureTimestamp) {
    return !hasFailedTooManyTimes(deployStatistics, recentFailureTimestamp);
  }
}
