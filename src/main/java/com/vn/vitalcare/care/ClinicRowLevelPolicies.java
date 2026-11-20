package com.vn.vitalcare.care;

import com.vn.vitalcare.entity.Alert;
import com.vn.vitalcare.entity.AlertRule;
import com.vn.vitalcare.entity.MonitoringOrder;
import com.vn.vitalcare.entity.PatientFeedback;
import com.vn.vitalcare.entity.ProfessionalLicense;
import com.vn.vitalcare.entity.ResultReturn;
import com.vn.vitalcare.entity.SyncBatch;
import com.vn.vitalcare.entity.SyncFailure;
import com.vn.vitalcare.share.security.rowlevel.DefaultScope;
import com.vn.vitalcare.share.security.rowlevel.FieldDescriptor;
import com.vn.vitalcare.share.security.rowlevel.RowLevelPolicySet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The resources that belong to one clinic by a plain {@code clinicId} and need nothing more.
 *
 * <p>Each was annotated {@code @RowLevelResource} and had "Cơ sở của mình" policies seeded, but no
 * policy set, so the registry did not know it and {@code scope} answered "unrestricted": a clinic
 * administrator read every clinic's feedback and licences (KAN-107, KAN-111), and the same for the
 * rest below. Registering them is what makes those policies apply.
 *
 * <p>{@code FULL} by default, as {@link DefaultScope#FULL} describes for a resource already running:
 * a role that has a scope is narrowed to it, one that has none sees what it saw yesterday.
 */
@Configuration
public class ClinicRowLevelPolicies {

    @Bean
    RowLevelPolicySet<PatientFeedback> feedbackPolicies() {
        return byClinic("feedback", PatientFeedback.class);
    }

    @Bean
    RowLevelPolicySet<ProfessionalLicense> licensePolicies() {
        return byClinic("licenses", ProfessionalLicense.class);
    }

    @Bean
    RowLevelPolicySet<ResultReturn> resultReturnPolicies() {
        return byClinic("result_returns", ResultReturn.class);
    }

    @Bean
    RowLevelPolicySet<Alert> alertPolicies() {
        return byClinic("alerts", Alert.class);
    }

    @Bean
    RowLevelPolicySet<AlertRule> alertRulePolicies() {
        return byClinic("alert_rules", AlertRule.class);
    }

    @Bean
    RowLevelPolicySet<MonitoringOrder> monitoringOrderPolicies() {
        return byClinic("monitoring_orders", MonitoringOrder.class);
    }

    @Bean
    RowLevelPolicySet<SyncBatch> deviceSyncPolicies() {
        return byClinic("device_syncs", SyncBatch.class);
    }

    @Bean
    RowLevelPolicySet<SyncFailure> syncFailurePolicies() {
        return byClinic("sync_failures", SyncFailure.class);
    }

    private static <T> RowLevelPolicySet<T> byClinic(String resource, Class<T> type) {
        return new RowLevelPolicySet<>() {
            @Override
            public String resource() {
                return resource;
            }

            @Override
            public Class<T> entityClass() {
                return type;
            }

            @Override
            public DefaultScope defaultScope() {
                return DefaultScope.FULL;
            }

            @Override
            public List<FieldDescriptor> fields() {
                return List.of(FieldDescriptor.id("id"), FieldDescriptor.column("clinicId", UUID.class));
            }

            @Override
            public Set<String> notCheckSafe() {
                return Set.of("id");
            }
        };
    }
}
