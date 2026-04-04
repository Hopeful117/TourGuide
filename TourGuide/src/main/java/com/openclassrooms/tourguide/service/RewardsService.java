package com.openclassrooms.tourguide.service;

import java.util.List;

import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

	// proximity in miles
    private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;
	
	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
	}
	
	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}
	
	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}
	// This method is called when a user gets a new location, and it checks if the user is near any attractions. If the user is near an attraction and has not already received a reward for that attraction, it adds a new reward to the user's rewards list.
    //Fixed ConcurrentModificationException by creating a copy of the user's visited locations and attractions lists, and by using a Set to track rewarded attractions for faster lookups.
    //Using Snapshot of User's visited locations and attractions to avoid ConcurrentModificationException, and using a Set to track rewarded attractions for efficient lookups.
    //The reward points are not being added to the user's rewards list because the getRewardPoints method is not being called correctly in the calculateRewards method.
    //Fixed by changing the condition in User's addUserReward method to check if there is already a reward for the attraction, and if so, it should not add a new reward to the user's rewards list.



	public void calculateRewards(User user) {
		List<VisitedLocation> userLocations = new ArrayList<>( user.getVisitedLocations());
		List<Attraction> attractions = new ArrayList<> (gpsUtil.getAttractions());
        Set<String> rewardedAttractions = user.getUserRewards().stream()
                .map(reward -> reward.attraction.attractionName)
                .collect(Collectors.toSet());
		
		for(VisitedLocation visitedLocation : userLocations) {
			for(Attraction attraction : attractions) {
				//if(user.getUserRewards().stream().filter(r -> r.attraction.attractionName.equals(attraction.attractionName)).count() == 0) {
				if(!rewardedAttractions.contains(attraction.attractionName) && nearAttraction(visitedLocation, attraction)) {

						user.addUserReward(new UserReward(visitedLocation, attraction, getRewardPoints(attraction, user)));
					    rewardedAttractions.add(attraction.attractionName);
				}
			}
		}
	}
	
	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) > attractionProximityRange ? false : true;
	}
	
	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getDistance(attraction, visitedLocation.location) > proximityBuffer ? false : true;
	}
	
	private int getRewardPoints(Attraction attraction, User user) {
		return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId());
	}
	
	public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
        return statuteMiles;
    }

}
