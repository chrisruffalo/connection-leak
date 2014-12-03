connection-leak
===============

Created after trying to deal with issues stemming from mysterious connection leaks on JBoss EAP 6.2.1.  The 
connection leaks are most likey due to interactions within the code that have not been discovered yet but
that doesn't change a few key facts:

* We don't know where they are
* We can't find them
* We need them to not break the server

And it's really that last part that is the most important.  We need to come up with a way to stop the server from breaking.

The CCM ([use-ccm](https://access.redhat.com/documentation/en-US/JBoss_Enterprise_Application_Platform/6.3/html/Administration_and_Configuration_Guide/sect-Datasource_Configuration.html)) 
should really be your first ticket out of trouble.  It **should** let you know when you are experiencing a leak and 
it should also deal with it, for the time being, if you configure it correctly.  The most relevant documentation I 
found is: [here](https://access.redhat.com/solutions/309913).  (RHN required.)

It basically breaks down to "set the debug attribute of the cached-connection-manager to 'true'". You also need to make
sure that you have 'INFO' logging turned on for the "org.jboss.jca" package in your `standalone.xml`.

Okay, so you've done that and MABYE you're still not having your leaks dealt with.  I found out, the hard way, that having
`jta="false"` in your datasource definition will *also* cause the CCM to not close orphaned connections.  Fantasic!

(You can fiddle around with your timeout options but... there's also a few things to remember about "idle" connections.  They're only idle if they have been *returned to the pool*.
So what that boils down to is that if you orphan a connection *it will never be idle.*)

So, what did I decide to do about it?  After batting around MANY different ideas (like watching the transaction status... 
which is pretty much what the CCM does to notify you of an orphaned connection) I settled on just keeping a list
of the connections and when they were created.  Then you can use an EJB timer to go back and remove the ones that
closed themselves (were properly returned to the pool) and then you can forcibly close the old ones (which will
also return them to the pool).  As a bonus the connection manager also closes orphans when it is destroyed.

There are the components of this example project:

* `DataSourceManager` - Creates and tracks all of the connection objects.
* `PruneTimer` - simple EJB timer that causes the prune action to happen every N seconds.  (15 in this case.)
* `TestServlet` - a servlet that allows the requester to specify if the application should leak a connection or not.

I had one little doubt left: performance.  I didn't want this to impact performance unduly.  So I used `ab` to benchmark
the servlet.  I used the options `ab -k -c 4 -t 30` and it maintaned around 350 requests per second the entire time
with both the connection tracking turned on and it turned off.  The memory profile (as viewed in JVisualVM) was pretty much
the same in both cases.  It might have needed some more GC with the connection tracking mechansim turned on but it
comes down to wanting to be able to prevent running out of connections.

What could be done to make it better?  Well... you could integrate with the connection pool statistics to only prune 
if you hit a certain threshold (like 50% or 75% full).  You could investigate better (or more compact) data structures
for use in storing the tracked connections.  You could add metadata to the connection that tracked where it was
used so that you could try and track down the leak.

This is just a sample project that shows how it can happen, how you can control it for testing, and how you can potentially
deal with it.
