package stress;

import hashmap.ConcurrentHashMap;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/** Two actors put different values for the same key; one value must win. */
@JCStressTest
@Outcome.Outcomes({
        @Outcome(id = "10", expect = Expect.ACCEPTABLE, desc = "first writer wins"),
        @Outcome(id = "20", expect = Expect.ACCEPTABLE, desc = "second writer wins"),
        @Outcome(id = "0", expect = Expect.FORBIDDEN, desc = "missing key after puts"),
})
@State
public class ConcurrentPutStressTest {

    private final ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
    private final Integer key = 7;

    @Actor
    public void writer1() {
        map.put(key, 10);
    }

    @Actor
    public void writer2() {
        map.put(key, 20);
    }

    @Arbiter
    public void arbiter(I_Result r) {
        Integer v = map.get(key);
        r.r1 = (v == null) ? 0 : v;
    }
}
