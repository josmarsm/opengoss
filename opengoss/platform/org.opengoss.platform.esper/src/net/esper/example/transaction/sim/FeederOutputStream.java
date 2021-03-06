package net.esper.example.transaction.sim;

import java.io.IOException;
import java.util.List;

import net.esper.client.EPRuntime;
import net.esper.example.transaction.FindMissingEventStmt;
import net.esper.example.transaction.TxnEventBase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class FeederOutputStream implements OutputStream
{
    private final EPRuntime runtime;
    private final long startTimeMSec;

    // We keep increasing the current time to simulate a 30 minute window
    private long currentTimeMSec;

    public FeederOutputStream(EPRuntime runtime)
    {
        this.runtime = runtime;
        startTimeMSec = System.currentTimeMillis();
        currentTimeMSec = startTimeMSec;
    }

    public void output(List<TxnEventBase> bucket) throws IOException
    {
        log.info(".output Feeding " + bucket.size() + " events");

        long startTimeMSec = currentTimeMSec;
        long timePeriodLength = FindMissingEventStmt.TIME_WINDOW_TXNC_IN_SEC * 1000;
        long endTimeMSec = startTimeMSec + timePeriodLength;
        sendTimerEvent(startTimeMSec);

        int count = 0, total = 0;
        for (TxnEventBase event : bucket)
        {
            runtime.sendEvent(event);
            count++;
            total++;

            if (count % 1000 == 0)
            {
                sendTimerEvent(startTimeMSec + timePeriodLength * total / bucket.size());
                count = 0;
            }

            if (count == 10000)
            {
                log.info(".output Completed " + total + " events");
                count = 0;
            }
        }

        sendTimerEvent(endTimeMSec);
        currentTimeMSec = endTimeMSec;

        log.info(".output Completed bucket");
    }

    private void sendTimerEvent(long msec)
    {
        log.info(".sendTimerEvent Setting time to now + " + (msec - startTimeMSec));
    }

    private static final Log log = LogFactory.getLog(TxnGenMain.class);
}
