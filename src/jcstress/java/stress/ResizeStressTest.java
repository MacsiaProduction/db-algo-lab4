package stress;

import hashmap.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jcstress.annotations.Actor;
import org.openjdk.jcstress.annotations.Arbiter;
import org.openjdk.jcstress.annotations.Expect;
import org.openjdk.jcstress.annotations.JCStressTest;
import org.openjdk.jcstress.annotations.Outcome;
import org.openjdk.jcstress.annotations.State;
import org.openjdk.jcstress.infra.results.I_Result;

/** Один сегмент с одним бакетом: вставки быстро триггерят resizeLocked; concurrent get не должен вернуть неверное значение. */
@JCStressTest
@Outcome.Outcomes({
        @Outcome(id = "0", expect = Expect.ACCEPTABLE, desc = "no wrong value observed"),
        @Outcome(id = "1", expect = Expect.FORBIDDEN, desc = "get returned wrong value for a key"),
})
@State
public class ResizeStressTest {

    /** Форсирует resize почти на каждом новом ключе. */
    private final ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>(1, 1);

    private final AtomicInteger nextPut = new AtomicInteger(0);
    private final AtomicInteger corrupted = new AtomicInteger(0);

    @Actor
    public void putter() {
        int k = nextPut.getAndIncrement();
        if (k < 4096) {
            map.put(k, k);
        }
    }

    @Actor
    public void getter() {
        int hi = nextPut.get();
        if (hi < 2) {
            return;
        }
        int k = hi - 2;
        Integer v = map.get(k);
        if (v != null && v.intValue() != k) {
            corrupted.compareAndSet(0, 1);
        }
    }

    @Arbiter
    public void arbiter(I_Result r) {
        r.r1 = corrupted.get();
    }
}
