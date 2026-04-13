package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;
import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import lombok.Setter;
import org.springframework.stereotype.Service;
import rewardCentral.RewardCentral;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static com.google.common.collect.Lists.partition;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;
    private static final long ATTRACTIONS_CACHE_TTL_MILLIS = 60_000;

    // proximity in miles
    private final int defaultProximityBuffer = 10;
    private final GpsUtil gpsUtil;
    private final RewardCentral rewardsCentral;
    private final ReentrantLock attractionsCacheLock = new ReentrantLock();
    private final Map<UUID, ReentrantLock> userLocks = new ConcurrentHashMap<>();
    private volatile List<Attraction> attractionsCache = List.of();
    private volatile long attractionsCacheExpiresAt = 0L;

    @Setter
    private int proximityBuffer = defaultProximityBuffer;

    final ExecutorService rewardsExecutor = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors() * 6)
    );

    public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
        this.gpsUtil = gpsUtil;
        this.rewardsCentral = rewardCentral;
    }

    public void setDefaultProximityBuffer() {
        proximityBuffer = defaultProximityBuffer;
    }



    public void calculateRewards(User user) {
        ReentrantLock userLock = getUserLock(user.getUserId());
        List<VisitedLocation> userLocations;
        Set<String> rewardedAttractions;

        // Snapshot atomique de l'etat utilisateur pour eviter les races.
        userLock.lock();
        try {
            userLocations = new ArrayList<>(user.getVisitedLocations());
            rewardedAttractions = user.getUserRewards().stream()
                    .map(reward -> reward.attraction.attractionName)
                    .collect(Collectors.toSet());
        } finally {
            userLock.unlock();
        }

        if (userLocations.isEmpty()) {
            return;
        }

        List<Attraction> attractions = getCachedAttractions();
        List<UserReward> newRewards = new ArrayList<>();
        Set<String> attractionNamesToReward = ConcurrentHashMap.newKeySet();
        attractionNamesToReward.addAll(rewardedAttractions);


        userLocations.forEach(visitedLocation -> {
            for (Attraction attraction : attractions) {
                if (nearAttraction(visitedLocation, attraction)
                        && attractionNamesToReward.add(attraction.attractionName)) {
                    int points = getRewardPoints(attraction, user.getUserId());
                    newRewards.add(new UserReward(visitedLocation, attraction, points));
                }
            }
        });
        // Synchronize on the user object to safely add new rewards to the user's rewards list without risking concurrent modification issues.
        userLock.lock();
        try {
            newRewards.forEach(user::addUserReward);
        } finally {
            userLock.unlock();
        }
    }
    // This method allows for asynchronous reward calculation, which can improve performance when processing rewards for multiple users concurrently.


    // This method calculates rewards for a list of users concurrently using CompletableFuture,
    // and waits for all calculations to complete before returning.
    public void CalculateRewardsForAllUsers(List<User> users) {
        List<CompletableFuture<Void>> futures = users.stream()
                .map(user -> CompletableFuture.runAsync(
                        () -> calculateRewards(user), rewardsExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();


    }


    public void shutdown() {
        rewardsExecutor.shutdownNow();
    }

    public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
        int attractionProximityRange = 200;
        return !(getDistance(attraction, location) > attractionProximityRange);
    }

    private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
        return !(getDistance(attraction, visitedLocation.location) > proximityBuffer);
    }

    int getRewardPoints(Attraction attraction, UUID user) {
        return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user);
    }

    private List<Attraction> getCachedAttractions() {
        long now = System.currentTimeMillis();
        List<Attraction> snapshot = attractionsCache;
        if (!snapshot.isEmpty() && now < attractionsCacheExpiresAt) {
            return snapshot;
        }

        attractionsCacheLock.lock();
        try {
            now = System.currentTimeMillis();
            if (attractionsCache.isEmpty() || now >= attractionsCacheExpiresAt) {
                attractionsCache = List.copyOf(Objects.requireNonNull(gpsUtil.getAttractions()));
                attractionsCacheExpiresAt = now + ATTRACTIONS_CACHE_TTL_MILLIS;
            }
            return attractionsCache;
        } finally {
            attractionsCacheLock.unlock();
        }
    }

    private ReentrantLock getUserLock(UUID userId) {
        return userLocks.computeIfAbsent(userId, ignored -> new ReentrantLock());
    }



    public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        return STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
    }

}
