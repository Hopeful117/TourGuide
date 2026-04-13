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
    /**********************************************************************************
     *
     * Methods Below: For Internal Testing
     *
     **********************************************************************************/
    private static final String tripPricerApiKey = "test-server-api-key";
    private static final long ATTRACTIONS_CACHE_TTL_MILLIS = 60_000;
    public final Tracker tracker;
    private final GpsUtil gpsUtil;
    private final RewardsService rewardsService;
    private final TripPricer tripPricer = new TripPricer();
    private final ReentrantLock attractionsCacheLock = new ReentrantLock();
    private volatile List<Attraction> attractionsCache = List.of();
    private volatile long attractionsCacheExpiresAt = 0L;
    // Database connection will be used for external users, but for testing purposes
    // internal users are provided and stored in memory
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
    // This method allows to track user location asynchronously, which can improve performance when tracking multiple users at the same time.
    // It uses a CompletableFuture to run the tracking logic in a separate thread,
    // and it handles any exceptions that may occur during the tracking process.
    public CompletableFuture<VisitedLocation> trackUserLocationAsync(User user) {
        return CompletableFuture.supplyAsync(() -> trackUserLocation(user), locationTrackingExecutor)
                .exceptionally(ex -> {
                    log.error("Unable to track location asynchronously for user {}", user.getUserId(), ex);
                    throw new CompletionException(ex);
                });
    }

    // This method tracks the user's location synchronously, which is useful for tracking a single user or when the tracking needs to be done in a specific order.
    public VisitedLocation trackUserLocation(User user) {
        VisitedLocation visitedLocation = gpsUtil.getUserLocation(user.getUserId());
        user.addToVisitedLocations(visitedLocation);
        rewardsService.calculateRewards(user);
        return visitedLocation;
    }
    // This method tracks the location of all users concurrently using CompletableFuture,
    // and waits for all tracking operations to complete before returning.
    public void trackAllUsers(List<User> users) {
        List<CompletableFuture<VisitedLocation>> futures = new ArrayList<>();
        for (User user : users) {
            futures.add(trackUserLocationAsync(user));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    // This method retrieves the nearby attractions for a given visited location, and it uses the rewards service to calculate the distance and reward points for each attraction.
    public List<NearbyAttractionDTO> getNearByAttractions(VisitedLocation visitedLocation) {
        List<NearbyAttractionDTO> nearbyAttractions = new ArrayList<>();

        // Using the cached attractions list to improve performance, and sorting the attractions by distance to the visited location before limiting the results to the 5 closest attractions.
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

    // This method retrieves the list of attractions from the cache if it is still valid, or it fetches the attractions from the GPS utility and updates the cache if it has expired.
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
