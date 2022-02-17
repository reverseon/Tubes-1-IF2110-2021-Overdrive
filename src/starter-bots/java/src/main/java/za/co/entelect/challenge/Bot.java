package za.co.entelect.challenge;

import za.co.entelect.challenge.command.*;
import za.co.entelect.challenge.entities.*;
import za.co.entelect.challenge.enums.PowerUps;
import za.co.entelect.challenge.enums.Terrain;
import za.co.entelect.challenge.entities.Lane;
import java.util.*;

import static java.lang.Math.max;

import java.security.SecureRandom;

public class Bot {

    private static final int maxSpeed = 9;
    private List<Command> directionList = new ArrayList<>();

    private final Random random;

    private final static Command ACCELERATE = new AccelerateCommand();
    private final static Command LIZARD = new LizardCommand();
    private final static Command OIL = new OilCommand();
    private final static Command BOOST = new BoostCommand();
    private final static Command EMP = new EmpCommand();
    private final static Command FIX = new FixCommand();
    private final static Command DO_NOTHING = new DoNothingCommand();
    private final static Command DECELERATE = new DecelerateCommand();
    private final static Command TURN_RIGHT = new ChangeLaneCommand(1);
    private final static Command TURN_LEFT = new ChangeLaneCommand(-1);
    private Command TWEET(int x, int y) {return new TweetCommand(y,x);}

    public Bot() {
        this.random = new SecureRandom();
        directionList.add(TURN_LEFT);
        directionList.add(TURN_RIGHT);
    }

    private Boolean hasPowerUp(PowerUps powerUpToCheck, PowerUps[] available) {
        for (PowerUps powerUp: available) {
            if (powerUp.equals(powerUpToCheck)) {
                return true;
            }
        }
        return false;
    }

    private List<Object> getBlocksInFront(int lane, int block, int viewDistance, GameState gameState, Boolean includeFirstBlock) {
        List<Lane[]> map = gameState.lanes;
        List<Object> blocks = new ArrayList<>();
        int startBlock = map.get(0)[0].position.block;
        Lane[] laneList = map.get(lane - 1);
        int wheretoStart = max(block - startBlock + (includeFirstBlock ? 0 : 1), 0);
        int wheretoEnd = block - startBlock + viewDistance;
        for (int i = wheretoStart; i <= wheretoEnd; i++) {
            if (laneList[i] == null || laneList[i].terrain == Terrain.FINISH) {
                break;
            }
            if (laneList[i].cyberTruck) blocks.add(Terrain.TRUCK);
            else if (laneList[i].occupiedByPlayerId == gameState.opponent.id) blocks.add(Terrain.OPPONENT);
            else blocks.add(laneList[i].terrain);

        }
        return blocks;
    }

    private int laneWeight(List<Object> laneTerrain) {
        int opIDX = laneTerrain.indexOf(Terrain.OPPONENT);
        int positionPts = 0;
        if (opIDX != -1) {
            int raw = laneTerrain.size() - opIDX;
            double scale = (raw / laneTerrain.size() * 7) - 3;
            positionPts = (int)(sigmoid(scale) * -15) - 1;
        }
        int mudfr = Collections.frequency(laneTerrain, Terrain.MUD);
        int oilfr = Collections.frequency(laneTerrain, Terrain.OIL_SPILL);
        int weight = Collections.frequency(laneTerrain, Terrain.BOOST) * 6 +
                Collections.frequency(laneTerrain, Terrain.LIZARD) * 5 +
                Collections.frequency(laneTerrain, Terrain.TWEET) * 5 +
                Collections.frequency(laneTerrain, Terrain.OIL_POWER) * 2 +
                Collections.frequency(laneTerrain, Terrain.EMP) * 5 +
                oilfr*oilfr * -3 +
                mudfr*mudfr * -3 +
                Collections.frequency(laneTerrain, Terrain.WALL) * -13 +
                Collections.frequency(laneTerrain, Terrain.TRUCK) * -13 +
                positionPts;
        // weighing the lane
        return weight;
    }

    private int prevSpdState(int curSpd) {
        switch (curSpd) {
            case 0:
                return 50; // ERROR CODE
            case 3:
                return 0;
            case 5:
                return 3;
            case 6:
                return 3;
            case 8:
                return 6;
            case 9:
                return 8;
            case 15:
                return 9;
            default:
                return -5000;
        }
    }
    private int nextSpdState(int curSpd) {
        switch (curSpd) {
            case 0:
                return 3;
            case 3:
                return 6;
            case 5:
                return 6;
            case 6:
                return 8;
            case 8:
                return 9;
            case 9:
                return 50; // ERROR CODE
            case 15:
                return 50; // ERROR CODE
            default:
                return -5000;
        }
    }

    private int maxSpd(int dmg) {
        switch(dmg) {
            case 0:
                return 15;
            case 1:
                return 9;
            case 2:
                return 8;
            case 3:
                return 6;
            case 4:
                return 3;
            case 5:
                return 0;
            default:
                return -999;
        }
    }
    private int cf(List<Object> list, Object any) {
        return Collections.frequency(list, any);
    }
    private double sigmoid(double x) {
        return 1 / (1 + Math.exp(-1*x));
    }
    private boolean hasObstacles(List<Object> lane) {
        return lane.contains(Terrain.OPPONENT) || lane.contains(Terrain.MUD) || lane.contains(Terrain.OIL_SPILL) || lane.contains(Terrain.WALL) || lane.contains(Terrain.TRUCK);
    }
    public Command run(GameState gameState) {
        Car myCar = gameState.player;
        Car opCar = gameState.opponent;
        // OBVIOUS AND IMMEDIATE ACTION
        if (myCar.damage > 3) return FIX; // Middle Ground
        if (myCar.speed < 3) return ACCELERATE; //  Accelerate to prevent 0 speed stuck

        List<Object> curLane = getBlocksInFront(myCar.position.lane, myCar.position.block, myCar.speed, gameState, false); // O(Speed)
        Integer curLaneW = laneWeight(curLane); // O(Speed)
        if (myCar.speed == 15 && hasPowerUp(PowerUps.LIZARD, myCar.powerups) && hasObstacles(curLane)) return LIZARD;
        if (!hasObstacles(curLane) && myCar.damage == 3) return FIX;

        // POWERUPS (EXCEPT LIZARD) DECISION HERE
        // BOOST -> when not and leading
        if (hasPowerUp(PowerUps.BOOST, myCar.powerups) && !myCar.boosting) {
            List<Object> blockIFBoost = getBlocksInFront(myCar.position.lane, myCar.position.block, maxSpd(myCar.damage), gameState, false);
            Integer truck = cf(blockIFBoost, Terrain.TRUCK);
            Integer wallo = cf(blockIFBoost, Terrain.WALL);
            Integer opb = cf(blockIFBoost, Terrain.OPPONENT);
            Integer mud = cf(blockIFBoost, Terrain.MUD);
            Integer oilsp = cf(blockIFBoost, Terrain.OIL_SPILL);
            if (!hasObstacles(blockIFBoost)) {
                if (myCar.damage == 2 || myCar.damage == 1) return FIX;
                else return BOOST;
            } else if (myCar.damage <= 2 && (mud + oilsp <= 1 ) && (truck+wallo+opb == 0) && (Math.abs(maxSpd(myCar.damage+1) - myCar.speed) > 3)) {
                return BOOST;
            }
        }

        if (myCar.speed <= 3 && !hasObstacles(getBlocksInFront(myCar.position.lane, myCar.position.block, nextSpdState(myCar.speed), gameState, false))) return ACCELERATE;

        if (!hasObstacles(curLane)) { // prioritize change lane / accel first if speed is too low before using power ups
            // EMP -> when not leading
            if (hasPowerUp(PowerUps.EMP, myCar.powerups)) {
                ArrayList<Integer> posibLane = new ArrayList<>();
                if (myCar.position.lane == 1) {posibLane.add(1); posibLane.add(2);}
                else if (myCar.position.lane == 4) {posibLane.add(3); posibLane.add(4);}
                else {posibLane.add(myCar.position.lane); posibLane.add(myCar.position.lane-1); posibLane.add(myCar.position.lane+1);}
                if (myCar.position.block < opCar.position.block && posibLane.contains(opCar.position.lane)) {
                    return EMP;
                }
            }
            // TWEET -> when not and leading
            if (hasPowerUp(PowerUps.TWEET, myCar.powerups)) {
                int mx = myCar.position.block;
                int my = myCar.position.lane;
                int ox = opCar.position.block;
                int oy = opCar.position.lane;
                int oSpd = opCar.speed;
                int desiredX = ox + (nextSpdState(oSpd) == 50 ? 15 : nextSpdState(oSpd)) + 1;
                int desiredY = oy;
                if (desiredX < mx || desiredY != my)
                    return TWEET(desiredX, desiredY);
            }
            // OIL -> when leading
            if (hasPowerUp(PowerUps.OIL, myCar.powerups)) {
                if (myCar.position.block > opCar.position.block && myCar.position.lane == opCar.position.lane) {
                    return OIL;
                }
            }
        }

        // LANE CHANGING DECISION
        if (myCar.position.lane == 1) {
            // can only turn right
            List<Object> rightLane = getBlocksInFront(myCar.position.lane+1, myCar.position.block, myCar.speed-1, gameState, true);
            Integer rightLaneW = laneWeight(rightLane);
            Integer lizardW = laneWeight(curLane.subList(max(curLane.size()-1, 0), curLane.size()));
            if (curLaneW < lizardW && rightLaneW < lizardW && hasPowerUp(PowerUps.LIZARD, myCar.powerups)) {
                return LIZARD; // Two Lane aren't worth it and has a Lizard, use LIZARD TO JUMP
            }
            if (curLaneW >= rightLaneW) { // BEST CONTENDER -> ACCELERATE, DO NOTHING
                // CHECK IF ITS WORTH IT TO ACCELERATE
                if (nextSpdState(myCar.speed) != 50 /* ERROR CODE */ && curLaneW <= laneWeight(getBlocksInFront(myCar.position.lane, myCar.position.block, nextSpdState(myCar.speed), gameState, false))) {
                    if (myCar.speed == maxSpd(myCar.damage) && !hasObstacles(getBlocksInFront(myCar.position.lane, myCar.position.block, nextSpdState(myCar.speed), gameState, false))) { // if you're at max speed and there's no obstacles
                        return FIX;
                    }

                    return ACCELERATE; // WHY NOT
                } else if (prevSpdState(myCar.speed) != 50 && curLaneW < laneWeight(getBlocksInFront(myCar.position.lane, myCar.position.block, prevSpdState(myCar.speed), gameState, false))) {
                    return DECELERATE;
                } else {
                    if (nextSpdState(myCar.speed) == 50) {
                        return ACCELERATE;
                    }
                    return DO_NOTHING;
                }
            } else {
                return TURN_RIGHT; // BETTER TURN RIGHT NOW
            }
        } else if (myCar.position.lane == 4) {
            // can only turn left
            List<Object> leftLane = getBlocksInFront(myCar.position.lane-1, myCar.position.block, myCar.speed-1, gameState, true);
            Integer leftLaneW = laneWeight(leftLane);
            Integer lizardW = laneWeight(curLane.subList(max(curLane.size()-1, 0), curLane.size()));
            if (curLaneW < lizardW && leftLaneW < lizardW && hasPowerUp(PowerUps.LIZARD, myCar.powerups)) {
                return LIZARD; // Two Lane aren't worth it and has a Lizard, use LIZARD TO JUMP
            }
            if (curLaneW >= leftLaneW) { // BEST CONTENDER -> ACCELERATE, DO NOTHING
                // CHECK IF ITS WORTH IT TO ACCELERATE OR DECELERATE
                if (nextSpdState(myCar.speed) != 50 && curLaneW <= laneWeight(getBlocksInFront(myCar.position.lane, myCar.position.block, nextSpdState(myCar.speed), gameState, false))) {
                    if (myCar.speed == maxSpd(myCar.damage) && !hasObstacles(getBlocksInFront(myCar.position.lane, myCar.position.block, nextSpdState(myCar.speed), gameState, false))) {
                        return FIX;
                    }
                    return ACCELERATE; // WHY NOT
                } else if (prevSpdState(myCar.speed) != 50 && curLaneW < laneWeight(getBlocksInFront(myCar.position.lane, myCar.position.block, prevSpdState(myCar.speed), gameState, false))) {
                    return DECELERATE;
                } else {
                    if (nextSpdState(myCar.speed) == 50) {
                        return ACCELERATE;
                    }
                    return DO_NOTHING;
                }
            } else {
                return TURN_LEFT; // BETTER TURN LEFT NOW
            }

        } else {
            // can be both
            List<Object> rightLane = getBlocksInFront(myCar.position.lane+1, myCar.position.block, myCar.speed-1, gameState, true);
            List<Object> leftLane = getBlocksInFront(myCar.position.lane-1, myCar.position.block, myCar.speed-1, gameState, true);
            Integer rightLaneW = laneWeight(rightLane);
            Integer leftLaneW = laneWeight(leftLane);
            Integer lizardW = laneWeight(curLane.subList(max(curLane.size()-1, 0), curLane.size()));
            if (curLaneW < lizardW && leftLaneW < lizardW && rightLaneW < lizardW && hasPowerUp(PowerUps.LIZARD, myCar.powerups)) {
                return LIZARD; // Three Lane aren't worth it and has a Lizard, use LIZARD TO JUMP
            }
            if (curLaneW >= rightLaneW && curLaneW >= leftLaneW) { // BEST CONTENDER -> ACCELERATE, DO NOTHING
                if (nextSpdState(myCar.speed) != 50 && curLaneW <= laneWeight(getBlocksInFront(myCar.position.lane, myCar.position.block, nextSpdState(myCar.speed), gameState, false))) {
                    if (myCar.speed == maxSpd(myCar.damage) && !hasObstacles(getBlocksInFront(myCar.position.lane, myCar.position.block, nextSpdState(myCar.speed), gameState, false))) {
                        return FIX;
                    }
                    return ACCELERATE; // WHY NOT
                } else if (prevSpdState(myCar.speed) != 50 && curLaneW < laneWeight(getBlocksInFront(myCar.position.lane, myCar.position.block, prevSpdState(myCar.speed), gameState, false))) {
                    return DECELERATE;
                } else {
                    if (nextSpdState(myCar.speed) == 50) {
                        return ACCELERATE;
                    }
                    return DO_NOTHING;
                }
            } else if (rightLaneW >= leftLaneW && rightLaneW >= curLaneW) {
                return TURN_RIGHT;
            } else {
                return TURN_LEFT;
            }
        }

    }
}
