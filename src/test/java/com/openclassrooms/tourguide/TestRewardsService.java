package com.openclassrooms.tourguide;

import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.service.RewardsService;
import com.openclassrooms.tourguide.service.TourGuideService;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;
import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.VisitedLocation;
import org.junit.jupiter.api.Test;
import rewardCentral.RewardCentral;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRewardsService {

    @Test
    public void userGetRewards() {
        GpsUtil gpsUtil = new GpsUtil();
        RewardsService rewardsService = new RewardsService(gpsUtil, new RewardCentral());

        InternalTestHelper.setInternalUserNumber(0);
        TourGuideService tourGuideService = new TourGuideService(gpsUtil, rewardsService);

        User user = new User(UUID.randomUUID(), "jon", "000", "jon@tourGuide.com");
        Attraction attraction = gpsUtil.getAttractions().get(0);
        user.addToVisitedLocations(new VisitedLocation(user.getUserId(), attraction, new Date()));
        tourGuideService.trackUserLocation(user);
        List<UserReward> userRewards = user.getUserRewards();
        tourGuideService.tracker.stopTracking();
        assertEquals(1, userRewards.size());
    }

    @Test
    public void isWithinAttractionProximity() {
        GpsUtil gpsUtil = new GpsUtil();
        RewardsService rewardsService = new RewardsService(gpsUtil, new RewardCentral());
        Attraction attraction = gpsUtil.getAttractions().get(0);
        assertTrue(rewardsService.isWithinAttractionProximity(attraction, attraction));
    }

    //Fixed the concurrent modification exception by creating a snapshot of the user's visited locations and attractions, and by using a Set to track rewarded attractions for efficient lookups.
    //The test still fails because the reward points are not being added to the user's rewards list, which is likely due to a bug in the calculateRewards method.
    //Fixed the issue by debugging the AddUserReward logic in User class and ensuring that the reward points are correctly calculated and added to the user's rewards list. The test now passes successfully.
    @Test
    public void nearAllAttractions() {
        GpsUtil gpsUtil = new GpsUtil();
        RewardsService rewardsService = new RewardsService(gpsUtil, new RewardCentral());
        rewardsService.setProximityBuffer(Integer.MAX_VALUE);

        InternalTestHelper.setInternalUserNumber(1);
        TourGuideService tourGuideService = new TourGuideService(gpsUtil, rewardsService);

        rewardsService.calculateRewards(tourGuideService.getAllUsers().get(0));
        List<UserReward> userRewards = tourGuideService.getUserRewards(tourGuideService.getAllUsers().get(0));
        tourGuideService.tracker.stopTracking();

        assertEquals(gpsUtil.getAttractions().size(), userRewards.size());
    }

}
