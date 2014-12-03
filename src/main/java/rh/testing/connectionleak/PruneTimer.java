package rh.testing.connectionleak;

import java.util.logging.Logger;

import javax.ejb.Schedule;
import javax.ejb.Singleton;
import javax.inject.Inject;

/**
 * EJB timer that kicks off the Data Manager's prune feature.
 * 
 */
@Singleton
public class PruneTimer {

    @Inject
    private DataSourceManager manager;
    
    @Schedule(persistent=false, second="*/15", minute="*", hour="*")
    public void scheduledPruing() {
        Logger.getLogger("pruner").info("starting prune...");
        this.manager.prune();
    }
    
}
