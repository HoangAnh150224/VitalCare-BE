package com.vn.vitalcare.share.validation;

import com.vn.vitalcare.care.clinic.dto.ClinicRequest;
import com.vn.vitalcare.care.staff.dto.EmployeeCreateRequest;
import com.vn.vitalcare.identity.rowlevel.dto.RowLevelPolicyRequest;
import com.vn.vitalcare.identity.rowlevel.dto.RowLevelPolicyPatchRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminRequiredFieldsTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    @AfterAll
    static void close() { FACTORY.close(); }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void clinicRejectsMissingFields(String value) {
        for (String field : new String[]{"name", "address", "contactPhone", "contactEmail", "contactPerson", "operatingStatus"}) {
            assertFalse(VALIDATOR.validateValue(ClinicRequest.class, field, value).isEmpty(), field);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void policyCreationRejectsMissingFields(String value) {
        for (String field : new String[]{"kind", "resource", "action", "name", "policyGroup", "description"}) {
            assertFalse(VALIDATOR.validateValue(RowLevelPolicyRequest.class, field, value).isEmpty(), field);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void policyUpdateCannotClearRequiredFields(String value) {
        for (String field : new String[]{"action", "name", "policyGroup", "description"}) {
            assertFalse(VALIDATOR.validateValue(RowLevelPolicyPatchRequest.class, field, value).isEmpty(), field);
        }
    }

    @Test
    void partialUpdateMayOmitUnchangedFields() {
        assertTrue(VALIDATOR.validate(new RowLevelPolicyPatchRequest(
                null, null, null, null, null, null, null, false)).isEmpty());
    }

    @Test
    void validClinicAndPolicyValuesPass() {
        assertTrue(VALIDATOR.validate(new ClinicRequest("Clinic", "Address", "0901234567",
                "contact@example.com", "Contact", "OPERATING")).isEmpty());
        assertTrue(VALIDATOR.validate(new RowLevelPolicyPatchRequest(
                null, "read", "Policy", "Group", "Description", null, null, false)).isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "        "})
    void staffRequiredTextCannotBeBlank(String value) {
        for (String field : new String[]{"phone", "fullName", "password"}) {
            assertFalse(VALIDATOR.validateValue(EmployeeCreateRequest.class, field, value).isEmpty(), field);
        }
    }
}
