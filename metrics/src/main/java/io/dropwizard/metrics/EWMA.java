package io.dropwizard.metrics;
import java.util.concurrent.TimeUnit;
import static java.lang.Math.exp;
/** 
 * An exponentially-weighted moving average.
 * @see <a href="http://www.teamquest.com/pdfs/whitepaper/ldavg1.pdf">UNIX Load Average Part 1: How
 *      It Works</a>
 * @see <a href="http://www.teamquest.com/pdfs/whitepaper/ldavg2.pdf">UNIX Load Average Part 2: Not
 *      Your Average Average</a>
 * @see <a href="http://en.wikipedia.org/wiki/Moving_average#Exponential_moving_average">EMA</a>
 */
public class EWMA {
  private static int INTERVAL=5;
  private static double SECONDS_PER_MINUTE=60.0;
  private static int ONE_MINUTE=1;
  private static int FIVE_MINUTES=5;
  private static int FIFTEEN_MINUTES=15;
  private static double M1_ALPHA=1 - exp(-INTERVAL / SECONDS_PER_MINUTE / ONE_MINUTE);
  private static double M5_ALPHA=1 - exp(-INTERVAL / SECONDS_PER_MINUTE / FIVE_MINUTES);
  private static double M15_ALPHA=1 - exp(-INTERVAL / SECONDS_PER_MINUTE / FIFTEEN_MINUTES);
  private volatile boolean initialized=false;
  private volatile double rate=0.0;
  private LongAdder uncounted=LongAdderFactory.create();
  private double alpha, interval;
  /** 
 * Creates a new EWMA which is equivalent to the UNIX one minute load average and which expects to be ticked every 5 seconds.
 * @return a one-minute EWMA
 */
  public static EWMA oneMinuteEWMA(){
    return new EWMA(M1_ALPHA,INTERVAL,TimeUnit.SECONDS);
  }
  /** 
 * Creates a new EWMA which is equivalent to the UNIX five minute load average and which expects to be ticked every 5 seconds.
 * @return a five-minute EWMA
 */
  public static EWMA fiveMinuteEWMA(){
    return new EWMA(M5_ALPHA,INTERVAL,TimeUnit.SECONDS);
  }
  /** 
 * Creates a new EWMA which is equivalent to the UNIX fifteen minute load average and which expects to be ticked every 5 seconds.
 * @return a fifteen-minute EWMA
 */
  public static EWMA fifteenMinuteEWMA(){
    return new EWMA(M15_ALPHA,INTERVAL,TimeUnit.SECONDS);
  }
  /** 
 * Create a new EWMA with a specific smoothing constant.
 * @param alpha        the smoothing constant
 * @param interval     the expected tick interval
 * @param intervalUnit the time unit of the tick interval
 */
  public EWMA(  double alpha,  long interval,  TimeUnit intervalUnit){
    this.interval=intervalUnit.toNanos(interval);
    this.alpha=alpha;
  }
  /** 
 * Update the moving average with a new value.
 * @param n the new value
 */
  public void update(  long n){
    uncounted.add(n);
  }
  /** 
 * Mark the passage of time and decay the current rate accordingly.
 */
  public synchronized void tick(){
    final long count=uncounted.sumThenReset();
    final double instantRate=count / interval;
    if (initialized) {
      rate+=(alpha * (instantRate - rate));
    }
 else {
      rate=instantRate;
      initialized=true;
    }
  }
  /** 
 * Returns the rate in the given units of time.
 * @param rateUnit the unit of time
 * @return the rate
 */
  public double getRate(  TimeUnit rateUnit){
    return rate * rateUnit.toNanos(1);
  }
  public EWMA(){
  }
}
