package com.betleopard.simple;

import com.betleopard.JSONSerializable;
import com.betleopard.domain.CentralFactory;
import com.betleopard.domain.Event;
import com.betleopard.domain.Horse;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.IMap;
import com.hazelcast.jet.*;
import static com.hazelcast.jet.Edge.between;
import static com.hazelcast.jet.KeyExtractors.entryKey;
import static com.hazelcast.jet.Processors.readMap;
import static com.hazelcast.jet.Processors.writeMap;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.hazelcast.jet.Processors.map;
import static com.hazelcast.jet.Processors.groupAndAccumulate;
import static com.hazelcast.jet.Processors.groupAndCollect;
import static com.hazelcast.jet.Processors.filter;
import static com.hazelcast.jet.Util.entry;

import static com.betleopard.simple.AnalysisSimple.*;
import java.util.Map.Entry;

/**
 * Simple example for getting started - uses Jet adapted from Java 8 
 * collections
 * 
 * @author kittylyst
 */
public class JetSimple {

    public final static String EVENTS_BY_NAME = "events_by_name";
    public final static String MULTIPLE = "multiple_winners";

    private final static Distributed.Supplier<Long> INITIAL_ZERO = () -> 0L;

    private final static Distributed.Function<Entry<String, Event>, Horse> HORSE_FROM_EVENT = e -> FIRST_PAST_THE_POST.apply(e.getValue());

    private JetInstance jet;

    public static void main(String[] args) throws Exception {
        CentralFactory.setHorses(SimpleHorseFactory.getInstance());
        CentralFactory.setRaces(new SimpleFactory<>());
        final JetSimple main = new JetSimple();
        main.setup();
        try {
            main.go();
            final Map<Horse, Long> multiple = main.getResults();
            System.out.println("Result set size: " + multiple.size());
            for (Horse h : multiple.keySet()) {
                System.out.println(h + " : " + multiple.get(h));
            }

        } finally {
            Jet.shutdownAll();
        }
    }

    public static DAG buildDag() {
        final DAG dag = new DAG();

        final Vertex source = dag.newVertex("source", readMap(EVENTS_BY_NAME));

        // How many events has this horse won? Use groupAndCollect() to reduce
        final Vertex count = dag.newVertex("reduce", groupAndAccumulate(HORSE_FROM_EVENT, INITIAL_ZERO, (tot, x) -> tot + 1));

//        // (HORSE, Event) -> ()
//        final Vertex combine = dag.newVertex("combine",
//                groupAndAccumulate(Entry<Horse,Long>::getKey, INITIAL_ZERO,
//                        (Long val, Entry<Horse,Long> winsPerHorse) -> val + winsPerHorse.getValue()));
        final Vertex multiple = dag.newVertex("multiple", filter((Entry<Horse, Long> ent) -> ent.getValue() > 1));

        final Vertex sink = dag.newVertex("sink", writeMap(MULTIPLE));

        return dag.edge(between(source.localParallelism(1), count))
//                .edge(between(winners.localParallelism(1), count))
                .edge(between(count, sink));
//                        .distributed()
//                        .partitioned(entryKey()))
//                .edge(between(combine, sink));
//                .edge(between(multiple, sink));
    }

    public void go() throws Exception {
        System.out.print("\nStarting up... ");
        long start = System.nanoTime();
        jet.newJob(buildDag()).execute().get();
        System.out.println("done in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " milliseconds.");
    }

    public Map<Horse, Long> getResults() {
        return jet.getMap(MULTIPLE);
    }

    public void setup() {
        jet = Jet.newJetInstance();

        final ClientConfig config = new ClientConfig();

        // Prime the map with data from disc
        final IMap<String, Event> name2Event = jet.getMap(EVENTS_BY_NAME);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(JetSimple.class.getResourceAsStream(HISTORICAL), UTF_8))) {
            r.lines().map(l -> JSONSerializable.parse(l, Event::parseBag)).forEach(e -> name2Event.put(e.getName(), e));
        } catch (IOException iox) {
            iox.printStackTrace();
        }
    }

}
