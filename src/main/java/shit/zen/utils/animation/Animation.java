package shit.zen.utils.animation;

/**
 * Lightweight time-based tween ported from NeoRaven's {@code xyz.daikirai.util.Animation}.
 *
 * <p>The upstream class drives a single value between {@code from} and {@code to} over a fixed
 * duration, using one of four hard-coded easing modes. It is intentionally allocation free: call
 * {@link #start()} when the state changes, then poll {@link #getValue(float, float, int)} (or
 * {@link #update()}) once per frame.</p>
 *
 * <p>Behaviour kept from upstream:</p>
 * <ul>
 *     <li>the same mode ids ({@code 0} linear, {@code 1} cubic in/out, {@code 2} quint out,
 *         {@code 3} bounce out) and the same bounce curve;</li>
 *     <li>the same public {@code start}, {@code last}, {@code value} and {@code duration} fields,
 *         so code copied from NeoRaven keeps compiling;</li>
 *     <li>a finished animation returns its cached value instead of recomputing it.</li>
 * </ul>
 *
 * <p>Fixes applied while porting:</p>
 * <ul>
 *     <li>progress is clamped to {@code [0, 1]}, so a stale clock can no longer produce values
 *         outside the requested range;</li>
 *     <li>the end value is clamped in the direction of travel, so animations that count
 *         <em>down</em> now actually run (upstream only clamped values above {@code to});</li>
 *     <li>a non-positive duration snaps to the target instead of dividing by zero.</li>
 * </ul>
 */
public class Animation {
    /** No easing: linear interpolation. */
    public static final int LINEAR = 0;
    /** Cubic ease-in-out, the default in most NeoRaven call sites. */
    public static final int CUBIC_IN_OUT = 1;
    /** Quint ease-out. */
    public static final int QUINT_OUT = 2;
    /** Ease-out bounce. */
    public static final int BOUNCE = 3;

    /** Timestamp (ms) the current animation started at. */
    public long start;
    /** Last value the animation settled on; used to skip work once finished. */
    public float last;
    /** Most recently computed value. */
    public float value;
    /** Duration in milliseconds. */
    public float duration;

    private float from;
    private float to;
    private int mode;
    private boolean finished;

    public Animation(float duration) {
        this.duration = duration;
    }

    /** Restarts the clock without changing the target range. */
    public void start() {
        this.last = 0.0f;
        this.start = System.currentTimeMillis();
        this.finished = false;
    }

    /** Restarts the clock and sets the range in one call. */
    public void start(float from, float to, int mode) {
        this.from = from;
        this.to = to;
        this.mode = mode;
        this.value = from;
        this.start();
    }

    /**
     * Returns the eased value for the current frame.
     *
     * @param from start of the range
     * @param to   end of the range
     * @param mode one of {@link #LINEAR}, {@link #CUBIC_IN_OUT}, {@link #QUINT_OUT}, {@link #BOUNCE}
     */
    public float getValue(float from, float to, int mode) {
        if (this.finished && this.from == from && this.to == to && this.mode == mode) {
            return this.last;
        }
        this.from = from;
        this.to = to;
        this.mode = mode;
        return this.sample();
    }

    /** Re-evaluates the stored range, for call sites that set the range once via {@link #start(float, float, int)}. */
    public float update() {
        if (this.finished) {
            return this.last;
        }
        return this.sample();
    }

    private float sample() {
        float progress = this.getProgress();
        float eased = this.ease(progress, this.mode);
        float v = this.from + eased * (this.to - this.from);
        if (progress >= 1.0f) {
            v = this.to;
            this.last = v;
            this.finished = true;
        }
        this.value = v;
        return v;
    }

    /** Progress of the current animation in {@code [0, 1]}. */
    public float getProgress() {
        if (this.duration <= 0.0f) {
            return 1.0f;
        }
        float progress = (float) (System.currentTimeMillis() - this.start) / this.duration;
        if (progress < 0.0f) {
            return 0.0f;
        }
        return progress > 1.0f ? 1.0f : progress;
    }

    private float ease(float t, int mode) {
        switch (mode) {
            case CUBIC_IN_OUT:
                return t < 0.5f
                        ? 4.0f * t * t * t
                        : (t - 1.0f) * (2.0f * t - 2.0f) * (2.0f * t - 2.0f) + 1.0f;
            case QUINT_OUT:
                return (float) (1.0 - Math.pow(1.0f - t, 5.0));
            case BOUNCE:
                return this.bounce(t);
            case LINEAR:
            default:
                return t;
        }
    }

    public int getValueInt(int from, int to, int mode) {
        return Math.round(this.getValue(from, to, mode));
    }

    public float bounce(float f) {
        double n = 7.5625;
        double d = 2.75;
        if ((double) f < 1.0 / d) {
            return (float) (n * (double) f * (double) f);
        }
        if ((double) f < 2.0 / d) {
            f = (float) ((double) f - 1.5 / d);
            return (float) (n * (double) f * (double) f + 0.75);
        }
        if ((double) f < 2.5 / d) {
            f = (float) ((double) f - 2.25 / d);
            return (float) (n * (double) f * (double) f + 0.9375);
        }
        f = (float) ((double) f - 2.625 / d);
        return (float) (n * (double) f * (double) f + 0.984375);
    }

    /** True once the current animation has reached its target. */
    public boolean isFinished() {
        return this.finished;
    }

    /** Drops any cached end value so the next sample runs again. */
    public void reset() {
        this.finished = false;
        this.last = 0.0f;
        this.start = System.currentTimeMillis();
    }

    public float getValue() {
        return this.value;
    }

    public int getValueInt() {
        return Math.round(this.value);
    }

    public float getDuration() {
        return this.duration;
    }

    public void setDuration(float duration) {
        this.duration = duration;
    }
}
