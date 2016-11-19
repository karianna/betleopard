package com.betleopard.hazelcast;

import com.betleopard.JSONSerializable;
import com.betleopard.domain.*;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.query.Predicate;
import com.hazelcast.spark.connector.rdd.HazelcastRDDFunctions;
import static com.hazelcast.spark.connector.HazelcastJavaPairRDDFunctions.javaPairRddFunctions;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import static java.time.temporal.TemporalAdjusters.next;
import java.util.*;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import scala.Tuple2;

/**
 *
 * @author ben
 */
public class LiveBetMain {

    private JavaSparkContext sc;
    private volatile boolean shutdown = false;
    private final HazelcastInstance client = HazelcastClient.newHazelcastClient();

    public static void main(String[] args) {
        final HazelcastFactory<Horse> stable = HazelcastHorseFactory.getInstance();
        CentralFactory.setHorses(stable);
        final HazelcastFactory<Race> raceFactory = new HazelcastFactory<>(Race.class);
        CentralFactory.setRaces(raceFactory);
        final HazelcastFactory<Event> eventFactory = new HazelcastFactory<>(Event.class);
        CentralFactory.setEvents(eventFactory);

        final LiveBetMain main = new LiveBetMain();
        main.init();
        main.run();
        main.stop();
    }

    private void init() {
        final SparkConf conf = new SparkConf()
                .set("hazelcast.server.addresses", "127.0.0.1:5701")
                .set("hazelcast.server.groupName", "dev")
                .set("hazelcast.server.groupPass", "dev-pass")
                .set("hazelcast.spark.valueBatchingEnabled", "true")
                .set("hazelcast.spark.readBatchSize", "5000")
                .set("hazelcast.spark.writeBatchSize", "5000");

        sc = new JavaSparkContext("local", "appname", conf);

        loadHistoricalRaces();
        createRandomUsers();
        createFutureEvent();
    }

    public void stop() {
        sc.stop();
    }

    public void run() {
        MAIN:
        while (!shutdown) {
            addSomeSimulatedBets();
            try {
                Thread.sleep(2);
            } catch (InterruptedException ex) {
                shutdown = true;
                continue MAIN;
            }
            recalculateRiskReports();
            try {
                Thread.sleep(2);
            } catch (InterruptedException ex) {
                shutdown = true;
                continue MAIN;
            }
        }
    }

    public void createFutureEvent() {
        // Grab some horses to use as runners in races
        final IMap<Horse, Object> fromHC = client.getMap("winners");
        final Set<Horse> horses = fromHC.keySet();

        // Now set up some future-dated events for next Sat
        final LocalDate nextSat = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.SATURDAY));
        LocalTime raceTime = LocalTime.of(11, 0); // 1100 start
        final Event e = CentralFactory.eventOf("Racing from Epsom", nextSat);
        final Set<Horse> runners = makeRunners(horses, 10);
        for (int i = 0; i < 18; i++) {
            final Map<Horse, Double> runnersWithOdds = makeSimulatedOdds(runners);
            final Race r = CentralFactory.raceOf(LocalDateTime.of(nextSat, raceTime), runnersWithOdds);
            e.addRace(r);

            raceTime = raceTime.plusMinutes(10);
        }
        final IMap<Long, Event> events = client.getMap("events");
        events.put(e.getID(), e);
    }

    public void addSomeSimulatedBets() {
        final IMap<Long, Event> events = client.getMap("events");
        final int numBets = 100;
        for (int i = 0; i < numBets; i++) {
            final Race r = getRandomRace(events);
            final Map<Long, Double> odds = r.getCurrentVersion().getOdds();
            final Horse shergar = getRandomHorse(r);
            final Leg l = new Leg(r, shergar, OddsType.FIXED_ODDS, 2.0);
            final Bet.BetBuilder bb = CentralFactory.betOf();
            final Bet b = bb.addLeg(l).stake(l.stake()).build();
                        // FIXME

        }
    }

    public void recalculateRiskReports() {
        final IMap<Long, Event> events = client.getMap("events");

        // Get all the users (can we partition users by activity)
        final IMap<Long, User> users = client.getMap("users");

        // Does this user have a bet on this Sat?
        final LocalDate thisSat = LocalDate.now().with(next(DayOfWeek.SATURDAY));
        final Predicate<Long, User> betOnSat = e -> {
            for (final Bet b : e.getValue().getKnownBets()) {
                INNER:
                for (final Leg l : b.getLegs()) {
                    final LocalDate legDate = l.getRace().getCurrentVersion().getRaceTime().toLocalDate();
                    if (legDate.equals(thisSat)) {
                        return true;
                    } else if (legDate.isBefore(thisSat)) {
                        break INNER;
                    }
                }
            }
            return false;
        };

        // Read bets that are ordered and happen on Sat
        final List<Bet> bets = new ArrayList<>();
        for (final User u : users.values(betOnSat)) {
            // Construct a map of races -> set of bets
            for (final Bet b : u.getKnownBets()) {
                BETS:
                for (final Leg l : b.getLegs()) {
                    final Race r = l.getRace();
                    final LocalDate legDate = r.getCurrentVersion().getRaceTime().toLocalDate();
                    if (legDate.equals(thisSat)) {
                        bets.add(b);
                    } else if (legDate.isBefore(thisSat)) {
                        break BETS;
                    }
                }
            }
        }

        final JavaRDD<Bet> betRDD = sc.parallelize(bets);
        final JavaPairRDD<Race, Set<Bet>> betsTmp = betRDD.flatMapToPair(b -> {
            final List<Tuple2<Race, Set<Bet>>> out = new ArrayList<>();
            for (final Leg l : b.getLegs()) {
                final Set<Bet> bs = new HashSet<>();
                bs.add(b);
                out.add(new Tuple2<>(l.getRace(), bs));
            }
            return out;
        });
        final JavaPairRDD<Race, Set<Bet>> betsByRace = 
                betsTmp.reduceByKey((s1, s2) -> {
                    s1.addAll(s2);
                    return s1;
                });
        
        // For each race, partition the set of bets by the horse they're backing
        // and compute the potential loss if that horse wins
        
        
        
        
    }

    Set<Horse> makeRunners(final Set<Horse> horses, int num) {
        if (horses.size() < num) {
            return horses;
        }
        final Set<Horse> out = new HashSet<>();
        final Iterator<Horse> it = horses.iterator();
        for (int i = 0; i < num; i++) {
            out.add(it.next());
        }
        return out;
    }

    Map<Horse, Double> makeSimulatedOdds(final Set<Horse> runners) {
        final Set<Horse> thisRace = makeRunners(runners, 4);
        final Map<Horse, Double> out = new HashMap<>();
        int i = 1;
        for (Horse h : thisRace) {
            out.put(h, Math.random() * i++);
        }
        return out;
    }

    Race getRandomRace(final IMap<Long, Event> eventsByID) {
        final List<Event> events = new ArrayList<>(eventsByID.values());
        final int rI = new Random().nextInt(events.size());
        final Event theDay = events.get(rI);
        final List<Race> races = theDay.getRaces();
        final int rR = new Random().nextInt(races.size());
        return races.get(rR);
    }

    Horse getRandomHorse(final Race r) {
        final List<Horse> geegees = new ArrayList<>(r.getCurrentVersion().getRunners());
        final int rH = new Random().nextInt(geegees.size());
        return geegees.get(rH);
    }

    void loadHistoricalRaces() {
        final JavaRDD<String> eventsText = sc.textFile("/tmp/historical_races.json");
        final JavaRDD<Event> events
                = eventsText.map(s -> JSONSerializable.parse(s, Event::parseBlob));

        final JavaPairRDD<Horse, Integer> winners
                = events.mapToPair(e -> new Tuple2<>(e.getRaces().get(0).getWinner().orElse(Horse.PALE), 1))
                .reduceByKey((a, b) -> a + b);

        final HazelcastRDDFunctions accessToHC = javaPairRddFunctions(winners);
        accessToHC.saveToHazelcastMap("winners");
    }

    void createRandomUsers() {
        // FIXME 
    }
}
