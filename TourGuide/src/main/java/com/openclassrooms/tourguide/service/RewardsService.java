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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;
    private static final long ATTRACTIONS_CACHE_TTL_MILLIS = 60_000;

    // proximity in miles
    private final int defaultProximityBuffer = 10;
    private final GpsUtil gpsUtil;
    private final RewardCentral rewardsCentral;
    private final Object attractionsCacheLock = new Object();
    private volatile List<Attraction> attractionsCache = List.of();
    private volatile long attractionsCacheExpiresAt = 0L;
    @Setter
    private int proximityBuffer = defaultProximityBuffer;

    private final ExecutorService rewardsExecutor = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors() * 2)
    );

    public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
        this.gpsUtil = gpsUtil;
        this.rewardsCentral = rewardCentral;
    }

    public void setDefaultProximityBuffer() {
        proximityBuffer = defaultProximityBuffer;
    }
    //This method is called when a user gets a new location, and it checks if the user is near any attractions. If the user is near an attraction and has not already received a reward for that attraction, it adds a new reward to the user's rewards list.
    //Using Snapshot of User's visited locations and attractions to avoid ConcurrentModificationException, and using a Set to track rewarded attractions for efficient lookups.
    //The reward points are not being added to the user's rewards list because the getRewardPoints method is not being called correctly in the calculateRewards method.
    //Fixed by changing the condition in User's addUserReward method to check if there is already a reward for the attraction, and if so, it should not add a new reward to the user's rewards list.


    public void calculateRewards(User user) {
        List<VisitedLocation> userLocations;
        Set<String> rewardedAttractions;

        // Snapshot atomique de l'etat utilisateur pour eviter les races.
        synchronized (user) {
            userLocations = new ArrayList<>(user.getVisitedLocations());
            rewardedAttractions = user.getUserRewards().stream()
                    .map(reward -> reward.attraction.attractionName)
                    .collect(Collectors.toSet());
        }

        if (userLocations.isEmpty()) {
            return;
        }

        List<Attraction> attractions = getCachedAttractions();
        List<UserReward> newRewards = new ArrayList<>();
        Set<String> attractionNamesToReward = new HashSet<>(rewardedAttractions);


        userLocations.parallelStream().filter(visitedLocation -> attractions.parallelStream().anyMatch(attraction ->
                !attractionNamesToReward.contains(attraction.attractionName) && nearAttraction(visitedLocation, attraction)))
                .forEach(visitedLocation -> attractions.parallelStream().filter(attraction ->
                        !attractionNamesToReward.contains(attraction.attractionName) && nearAttraction(visitedLocation, attraction))
                        .forEach(attraction -> {
                            int points = getRewardPoints(attraction, user.getUserId());
                            newRewards.add(new UserReward(visitedLocation, attraction, points));
                            attractionNamesToReward.add(attraction.attractionName);
                        }));

        synchronized (user) {
            newRewards.forEach(user::addUserReward);
        }
    }

    public CompletableFuture<Void> calculateRewardsAsync(User user) {
        return CompletableFuture.runAsync(() -> calculateRewards(user), rewardsExecutor);
    }

    public CompletableFuture<Void> calculateRewardsForUsersAsync(List<User> users) {
        List<CompletableFuture<Void>> futures = users.stream()
                .map(this::calculateRewardsAsync)
                .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
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

        synchronized (attractionsCacheLock) {
            now = System.currentTimeMillis();
            if (attractionsCache.isEmpty() || now >= attractionsCacheExpiresAt) {
                attractionsCache = List.copyOf(Objects.requireNonNull(gpsUtil.getAttractions()));
                attractionsCacheExpiresAt = now + ATTRACTIONS_CACHE_TTL_MILLIS;
            }
            return attractionsCache;
        }
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
