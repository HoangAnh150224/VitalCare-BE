package com.vn.vitalcare.it;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Like {@link IntegrationTestBase} but each request commits, as in the running system: no test transaction holds a
 * Hibernate session open across the request, so what only works inside one (a lazy association read after the
 * service's transaction ended, with open-in-view off) fails here as it would for a user, and what runs after commit
 * (alerts, notifications, live pushes) runs. Tests in these suites only create data of their own (fresh 097 numbers,
 * IT- device codes) and never change seed rows, so the shared vitalcare_it database stays usable run after run.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public abstract class CommittedIntegrationTest extends IntegrationTestBase {
}
