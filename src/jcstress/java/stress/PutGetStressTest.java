package stress;

import hashmap.ConcurrentHashMap;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/**
 * One thread puts (k,1), another reads k. Final map state should be absent or present with 1.
 */
@JCStressTest
@Outcome.Outcomes({
        @Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "key absent (put not yet visible to arbiter)"),
        @Outcome(id = "1", expect = Expect.ACCEPTABLE, desc = "key present with value 1"),
})
@State
public class PutGetStressTest {

    private final ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
    private final Integer key = 42;

    @Actor
    public void writer() {
        map.put(key, 1);
    }

    @Actor
    public void reader() {
        map.get(key);
    }

    @Arbiter
    public void arbiter(I_Result r) {
        Integer v = map.get(key);
        r.r1 = (v == null) ? 0 : v;
    }
}
