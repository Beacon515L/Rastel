# Rastel - crowdsourced COVID-19 contact tracing
## Why the name?
From Latin *rastellus*, diminuitive of *rastrum,* "rake", or more precisely "tool of scraping".  *Rastrum* is the origin of *rastro* in several Romance languages (esp. Portuguese and Spanish) where in addition to being this tool, it has the figurative meaning of being a trail left behind (either by such a tool, or more generally by anything).

I chose it because I liked the metaphor of contact tracing being like running a fine-toothed comb through traces left behind by people as they pass along.

## What?

I'm a nerd.  Really, that's a who, not a what.

Rastel is a client-server application which implements rudimentary contact-tracing based on correlating location logs from devices carried by users running a mobile application.

## Why?

The specific motivator was this press conference by Scott Morrison, Prime Minister of Australia, where he announced changes to the close contact definition essentially to ratify their lack of policy foresight in demanding a return to a suppression strategy without any adequate provision or requisition for PCR or rapid antigen testing, concurrently with the rise of Omicron in Australia.  While some state governments have indicated an intention to push back on this, New South Wales - the most populous state and where Morrison's electorate is - have not.

Prior to this I had been concerned about the stresses placed on the contact tracing system, no doubt due to the immense case loads, but the redefinition of close contacts to requiring three or more hours of contact is, in my view, completely at odds with the infection rate of Omicron, and completely removes any confidence I had in the system.

So I built my own.  I have previous experience working with spatiotemporal correlation problems and realized I could adapt similar approaches to contact tracing.

## How?
Rastel is basically four components:
1. A MySQL database containing logs of users' positions at points in time, and any tests they have had.
2. A web API fronting this database allowing these locations and tests to be securely reported.
3. A background process running against the database to compute close contacts and alert people to get tested if it suspects they have been exposed.
4. A client application. I am developing one for Android; in principle, any app which correctly implements the API can be validly used.


## Okay, but how?
A user registers for Rastel by downloading a suitable client app and supplying their email address, desired password and time zone (for correctly reporting times when emails are sent).  No other personal information is captured ever.

The app uses whatever sensors are available to it to capture the devices (and theoretically that user's) location, to the best accuracy available.  It logs this location once per minute, and periodically uploads them to a server via the API.  Should the user have any reason to get tested, a facility is also provided to report the result of that test in the app.

Meanwhile, every fifteen minutes, the server crunches all the location logs it has and performs the following steps:
1. It identifies logs which put any two or more users within close physical contact at the same time.
2. Of those, it flags logs where at least one of the users would have been, on the basis of their test results, COVID-19 positive.
3. It sends emails to any user with logs so flagged to alert them that they may have been exposed and should get tested.
4. It purges location data old enough to have no current relevance (two or more weeks old).

## Yes but *how?*
Rastel's client application is nothing more special than a background service polling location services on the mobile device and periodically calling an API, together with an interface for reporting test results.

The interesting stuff is all in the server's background process and the overall design of the database schema.  While the client device submits exact latitudes, longitudes and times to the API, the API intentionally granularizes the data to optimize correlation.  Specifically, three steps are taken:
1. The coordinates are rounded to a precision of around 11 metres (36 feet).
2. The coordinates are then Cantor-paired to produce a number uniquely identifying one of four 11x11m grid squares on Earth (disambiguated by a quadrant number).
3. The times are rounded to a precision of 60 seconds.

The step of identifying logs where any two or more uses are in close contact then reduces to a problem of identifying logs which agree in Cantor-paired coordinates and granularized time interval.  The step flagging these logs where their users were positive is then simply a matter of interpreting windows of positivity from their test results.  Sending notifications is then a straightforward per-user queue.

Cantor-pairing is obviously much less accurate than recursive haversine/great-circle math on precise coordinates/times, especially with the step of intentionally reducing coordinate precision upfront, but it is *much* faster and database-indexable.  The overall performance of the system turns particularly on the distance and time resolutions configured; as well it is worth considering that precision better than 10 metres is rarely achievable on user-portable devices in most urban environments.

## Where?

Nowhere yet.  I have a lot of proving to do before I could make this any kind of available.  I will probably throw it up on some AWS EC2 instance or something, not sure yet.

## When?

IDK.  ASAP because there is a clear need, but I do also have a day job.  I am working on this as I can find time.

## So what?

It is true that everything we know about Omicron suggests it is significantly less deadly than Delta, which it is comprehensively displacing.  It is also true that it is significantly more infectious and is likely to kill or harm people not so much through its own direct lethality, but by stressing the bandwidth of acute healthcare settings such as hospital inpatient and ICU wards such that people who require these services for reasons not necessarily connected with COVID may be turned away.

It is also true that Omicron is, compared with Delta, highly vaccine-resistant, and natural (herd) immunity does not appear to be long-lasting or particularly effective, and the debilitating effects of

The scarier, though I hope less likely, possibility, is that if another variant were to mutate from Omicron, there is no guarantee such a mutation would follow the general pattern of viral evolution towards increased infectiousness at the expense of lethality.  This occurred during the 1918 influenza pandemic to devastating effect, it is theorized, [because the selection pressures were inverted](https://www.newyorker.com/magazine/1997/09/29/the-dead-zone) - mildly ill soldiers remained at their posts, while the gravely ill were shipped to crowded hospital facilities.

Dense concentrations of acutely unwell people treated by significantly overstressed healthcare workers combined with a population that, insofar as they need not be in hospital, appear to generally be keeping to themselves and not mixing with the general population beyond necessity, rather too closely resembles the selection pressures of the 1918 pandemic for my liking.

While there is probably no accounting for the behaviour of the general population - and understandably so while long-COVID remains a concern - I see value in maintaining surveillance of viral spread, both as a means of coordinating voluntary self-isolation to remove transmission vectors from the virus, and to fill what I find is an unethical gap created by the National Cabinet decision to redefine close contacts (in that public contact tracing efforts will now intentionally omit or delay informing people of the fact of their exposure in circumstances where, prior to that decision, they would have been duly informed).

## UML diagrams
These are [Mermaid](https://mermaidjs.github.io/) diagrams and won't (yet) display in Github.  Give your support to [Github adding Mermaid support](https://github.community/t/feature-request-support-mermaid-markdown-graph-diagrams-in-md-files/1922).
```sequenceDiagram
actor u as User
participant c as Rastel mobile device (Android etc.)
participant a as Rastel API
participant s as Rastel cron processor
participant d as MySQL Database
note over u,c: Registration
u->>+c: User registers with email/password/timezone
activate u
c->>+a: POST /rest/v1/user
a->>a: Email vailidation
note right of a: https://isemail.info
a->>a: Timezone validation
note right of a: https://www.php.net/manual/en/timezones.php
a->>a: Hash the password
a->>+d: Email address
d-->>-a: user details, or nonexistence
alt user is registered and email is verified
		a-->>c: Err ALREADY_REGISTERED
		c-->>u: User is already registered, please log in
	else 
		alt email is not verified
			a->>d: Update the user's timezone and password
			d-->>a: User ID
		else user isn't registered
			a->>+d: Create user account (email not yet verified)
			d-->>-a: User ID
		end
	a-->>c: User ID and details
    c->>c: Store user details
	c-->>-u: Expect activation email
    deactivate u
	a-->>-u: Activation email
    activate u
    u->>+a: Activation link
    a-->>-u: Welcome email
deactivate u
end
note over u,c: Device authorization (login)
activate u
u->>+c: Log in with email and password
c->>+a: GET /rest/v1/user
a->>+d: Email address
d-->>-a: User's password hash, or nonexistence
alt user doesn't exist
	a->>a: Wait random time to simultate password validation
	note right of a: To combat timing attacks
	a-->>c: Err AUTH_FAIL
	c-->>u: Login failed, please try again
else user exists
	a->>a: validate the pasword
		alt password is invalid
			a-->>c: Err AUTH_FAIL
			c-->>u: Login failed, please try again
		else password is valid
			a->>a: Generate a JSON Web Token
			note right of a: RS256 HMAC
			a-->>-c: JWT
			c->>c: Store the JWT persistently
			c-->>u: Login successful
		end
end
deactivate u
note over u,c: Per request authorization in general
activate c
note right of a: This exchange happens in the headers of every request
c->>+a: JWT
note right of a: marked "REQUIRES AUTHENTICATION".
alt token is malformed or expired
    a-->>c: Err MALFORMED_TOKEN
else token does not match the user identified in the request
    a-->>c: Err AUTH_FAILURE
else token is valid
    opt token is close to expiry
	    a->>a: New token generated to be provided in response
    end
    a->>-a: Request proceeds
    deactivate c
end
note over u,c: User details update [REQUIRES AUTHENTICATION]
activate u
u->>+c: New email, password and timezone, and old password
c->>+a: PUT /rest/v1/user
a->>a: Validate email and timezone
a->>a: Hash the new password
a->>+d: User ID
d-->>-a: User details
a->>a: Verify old password
alt old password is invalid
    a-->>c: Err AUTH_FAIL
    c-->>u: Please check your old password
else old password is valid
    a->>+d: Update email, password and timezone
    d-->>-a: Updated details
    a-->>-c: Updated details
    c->>c: Store updated details
    c-->>-u: Details successfully updated
end
note over u,c: Clock offset measurement
loop every 24 hours
    activate c
    c->>+a: GET /rest/v1/serverTime
    a-->>-c: Current server time
    c->>-c: Store current local time and server 
    note right of c: This allows the API to work out the device's clock offset.
end
note over u,c: Device log management [REQUIRES AUTHENTICATION]
loop every 60 seconds
    activate c
    c->>c: Locally log current location
    deactivate c
end
loop every 15 minutes
    activate c
    c->>+a: POST /rest/v1/log
    a->>a: Validate input parameters
    a->>a: Compute request time offset
    loop for each submitted location
        alt log is invalid or older than age gate
            a->>a: Remove
        else log is valid
            a->>a: Granularize timestamp
            a->>a: Granularize coordinates
            a->>a: Cantor-pair coordinates
        end
    end
    opt at least one valid location was processed
        a->>+d: Begin transaction
        d-->>a: 
        loop for each location
            a->>d: Write
            d-->>a: 
        end
        a->>d: Commit
        d-->>-a: 
    end
a-->>-c: List of locations written
c->>c: Update local logs
deactivate c
end
loop every hour
    activate c
    c->>+a: GET /rest/v1/log
    a->>+d: User ID
    d-->>-a: All logs for this user
    a-->>-c: All logs for this user
    c->>c: Update local logs
    deactivate c
end
note over u,c: Test submission [REQUIRES AUTHENTICATION]
activate u
u->>+c: Submit test result
c->>+a: POST /rest/v1/test
a->>a: Validate parameters
a->>a: Compute request time offset
a->>+d: Insert test details
d-->>-a: 
a-->>-c: 
c-->>-u: Test submitted
deactivate u
note over u,c: Test history [REQUIRES AUTHENTICATION]
activate u
u->>+c: View test history
c->>+a: GET /rest/v1/test
a->>+d: User ID
d-->>-a: Test history
a-->>-c: Test history
c-->>-u: View list of tests
deactivate u
note over s,d: Background process
loop every 5 minutes
    activate s
    s->>s: Fix the batch's reference time
    s->>+d: Check whether to run
    d-->>-d: Last time any log still in the database was processed
    opt latest processed log older than PROCESSING_DELAY or doesn't exist
        s->>+d: Delete logs older than PROCESSING DELAY
        d-->>-s: Number of records removed
        s-->>+d: Geotemporally corelate logs
        d->>d: Load all unprocessed logs
        d->>d: Aggregate these logs for users by location and time
        d->>d: Flag all these logs where users present > 1
        d->>d: Aggregate unflagged logs against existing logs
        d->>d: Flag remaining logs against existing logs
        d-->>-s: Number of records flagged
        s->>+d: Flag positive contacts
        d->>d: Load all correlated, flagged and notified logs
        d->>d: Load all tests for users in those logs
        note left of d: Derive "positivity events" - times at which COVID positive status may have changed
        d->>d: Derive positivity events from positive tests
        d->>d: Derive positivity events from nominal quarantine departures
        d->>d: Derive positivity events from infectious periods
        note left of d: Remove inferred events contradicted by tests
        loop while at least one inferred positivity event is unconfirmed
            d->>d: Decrement pointers
            d->>d: Confirm any event with no preceding event
            d->>d: Confirm/reject any event now pointing to a test
        end
        d->>d: Delete all rejected events
        note left of d: Compute positivity windows
        d->>d: Determine new positivity event sequence maxima
        loop while at least one positive test has no identified following negative event
            d->>d: Increment pointers
            d->>d: Remove positive events with no following events at all from the loop
            d->>d: Remove positive events now pointing to a negative event
        end
        d->>d: Construct positivity period table
        d->>d: Flag every log for each user within their own positivity periods directly
        d->>d: Temporarily flag all logs spatiotemporally correlated with these
        d->>d: Compute the required close contact time interval hit count
        loop for each time interval in a period CLOSE_CONTACT_TIME_MAX wide, centered on each log
            d->>d: Increment the hit count for each log spatiotemporally correlated with a confirmed flagged log
            d->>d: Confirm (and stop iterating) any log that has met the hitcount
            d->>d: Advance the time interval
        end
        d->>d: Flag any log that has not met the hit count as not confirmed
        d->>d: Update the database
        d-->>-s: Number of records processed
        s->>+d: Obtain the list of required email notifications
        d->>d: Identify all users with flagged, non-notified logs
        d->>d: Identify which of these users should already know to be in isolation based on tests
        d-->>-s: IDs, emails, timezones and theoretical quarantine departure dates of each user requring notification
        loop for each user requiring notification
            alt if the user should already know to be in quarantine
                s->>s: Skip
            else if the user needs to be notified
                s->>+d: Begin transaction
                d-->>s: 
                s->>d: User ID
                d-->>s: Every flagged, non-notified log for this user
                s->>s: Parse into a list of contact windows
                s->>u: Send an email containing these windows
                s->>d: Update all non-notified logs to notified and commit
                d-->>-s: 
            end
        end
    end
    deactivate s
end```