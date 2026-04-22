package com.openclassrooms.tourguide.service;

import com.openclassrooms.tourguide.dto.NearbyAttractionDTO;
import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.tracker.Tracker;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;
import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tripPricer.Provider;
import tripPricer.TripPricer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.IntStream;

@Service
@Slf4j
public class TourGuideService {
    private static final String tripPricerApiKey = "test-server-api-key";
    private static final long ATTRACTIONS_CACHE_TTL_MILLIS = 60_000;
    public final Tracker tracker;
    private final GpsUtil gpsUtil;
    private final RewardsService rewardsService;
    private final TripPricer tripPricer = new TripPricer();
    private final ReentrantLock attractionsCacheLock = new ReentrantLock();
    private volatile List<Attraction> attractionsCache = List.of();
    private volatile long attractionsCacheExpiresAt = 0L;
    // In test mode, users are kept in-memory.
    private final Map<String, User> internalUserMap = new HashMap<>();
    boolean testMode = true;


     private final ExecutorService locationTrackingExecutor = Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors() * 2)
    );




    public TourGuideService(GpsUtil gpsUtil, RewardsService rewardsService) {
        this.gpsUtil = gpsUtil;
        this.rewardsService = rewardsService;

        Locale.setDefault(Locale.US);

        if (testMode) {
            log.info("TestMode enabled");
            log.debug("Initializing users");
            initializeInternalUsers();
            log.debug("Finished initializing users");
        }
        tracker = new Tracker(this);
        addShutDownHook();
    }

    public List<UserReward> getUserRewards(User user) {
        return user.getUserRewards();
    }


    public VisitedLocation getUserLocation(User user) {
        return (!user.getVisitedLocations().isEmpty()) ? user.getLastVisitedLocation()
                : trackUserLocation(user);
    }

    public User getUser(String userName) {
        return internalUserMap.get(userName);
    }

    public List<User> getAllUsers() {
        return new ArrayList<>(internalUserMap.values());
    }

    public void addUser(User user) {
        if (!internalUserMap.containsKey(user.getUserName())) {
            internalUserMap.put(user.getUserName(), user);
        }
    }


    public List<Provider> getTripDeals(User user) {
        int cumulativeRewardPoints = user.getUserRewards().stream().mapToInt(UserReward::getRewardPoints).sum();
        List<Provider> providers = tripPricer.getPrice(tripPricerApiKey, user.getUserId(),
                user.getUserPreferences().getNumberOfAdults(), user.getUserPreferences().getNumberOfChildren(),
                user.getUserPreferences().getTripDuration(), cumulativeRewardPoints);
        user.setTripDeals(providers);
        return providers;
    }


    /**
     * Tracks one user synchronously and computes rewards for the new visited location.
     */
    public VisitedLocation trackUserLocation(User user) {
        VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
        user.addToVisitedLocations(visitedLocation);
        rewardsService.calculateRewards(user);
        return visitedLocation;
    }

    /**
     * Tracks all users concurrently and returns only when all location updates are complete.
     */
    public void trackAllUsers(List<User> users) {
        List<CompletableFuture<VisitedLocation>> futures = users.stream()
                .map(user -> CompletableFuture.supplyAsync(() -> trackUserLocation(user), locationTrackingExecutor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    /**
     * Returns the 5 closest attractions for a visited location with computed distance and points.
     */
    public List<NearbyAttractionDTO> getNearByAttractions(VisitedLocation visitedLocation) {
        List<NearbyAttractionDTO> nearbyAttractions = new ArrayList<>();

        // Sort all attractions by distance and keep only the five nearest.
        List<Attraction> attractions = getCachedAttractions().stream().sorted((a1, a2) -> Double.compare(rewardsService.getDistance(visitedLocation.location, a1),
                        rewardsService.getDistance(visitedLocation.location, a2)))
                .limit(5)
                .toList();
        attractions.forEach(attraction -> {
            double distanceInMiles = rewardsService.getDistance(visitedLocation.location, attraction);
            int rewardPoints = rewardsService.getRewardPoints(attraction, visitedLocation.userId);
            nearbyAttractions.add(new NearbyAttractionDTO(attraction.attractionName, attraction.latitude, attraction.longitude,
                    visitedLocation.location.latitude, visitedLocation.location.longitude, distanceInMiles, rewardPoints));
        });

        return nearbyAttractions;
    }

    /**
     * Returns cached attractions and refreshes the cache when TTL has expired.
     */
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

    private void addShutDownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                tracker.stopTracking();
                locationTrackingExecutor.shutdownNow();
                rewardsService.shutdown();
            }
        });
    }

    private void initializeInternalUsers() {
        IntStream.range(0, InternalTestHelper.getInternalUserNumber()).forEach(i -> {
            String userName = "internalUser" + i;
            String phone = "000";
            String email = userName + "@tourGuide.com";
            User user = new User(UUID.randomUUID(), userName, phone, email);
            generateUserLocationHistory(user);

            internalUserMap.put(userName, user);
        });
        log.debug("Created {} internal test users.", InternalTestHelper.getInternalUserNumber());
    }

    private void generateUserLocationHistory(User user) {
        IntStream.range(0, 3).forEach(i -> {
            user.addToVisitedLocations(new VisitedLocation(user.getUserId(),
                    new Location(generateRandomLatitude(), generateRandomLongitude()), getRandomTime()));
        });
    }

    private double generateRandomLongitude() {
        double leftLimit = -180;
        double rightLimit = 180;
        return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
    }

    private double generateRandomLatitude() {
        double leftLimit = -85.05112878;
        double rightLimit = 85.05112878;
        return leftLimit + new Random().nextDouble() * (rightLimit - leftLimit);
    }

    private Date getRandomTime() {
        LocalDateTime localDateTime = LocalDateTime.now().minusDays(new Random().nextInt(30));
        return Date.from(localDateTime.toInstant(ZoneOffset.UTC));
    }

}
