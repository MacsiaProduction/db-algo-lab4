package stress;

import hashmap.ConcurrentHashMap;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/** Two actors each merge +1; linearizable result should be 2. */
@JCStressTest
@Outcome.Outcomes({
        @Outcome(id = "2", expect = Expect.ACCEPTABLE, desc = "both increments applied"),
        @Outcome(id = "1", expect = Expect.FORBIDDEN, desc = "lost update"),
        @Outcome(id = "0", expect = Expect.FORBIDDEN, desc = "missing value"),
})
@State
public class MergeAtomicityStressTest {

    private final ConcurrentHashMap<Integer, Long> map = new ConcurrentHashMap<>();
    private final Integer key = 99;

    @Actor
    public void a1() {
        map.merge(key, 1L, (a, b) -> a + b);
    }

    @Actor
    public void a2() {
        map.merge(key, 1L, (a, b) -> a + b);
    }

    @Arbiter
    public void arbiter(I_Result r) {
        Long v = map.get(key);
        r.r1 = (v == null) ? 0 : (int) v.longValue();
    }
}
