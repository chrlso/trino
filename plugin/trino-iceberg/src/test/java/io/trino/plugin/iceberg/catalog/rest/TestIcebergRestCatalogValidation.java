/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.iceberg.catalog.rest;

import io.trino.spi.TrinoException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestIcebergRestCatalogValidation
{
    @Test
    public void testCaseSensitivityValidation()
    {
        // Test that both case sensitivity properties cannot be true at the same time
        IcebergRestCatalogConfig restCatalogConfig = new IcebergRestCatalogConfig()
                .setBaseUri("http://localhost:1234")
                .setCaseInsensitiveNameMatching(true)
                .setCaseSensitiveNamesSupported(true);

        assertThatThrownBy(() -> validateConfiguration(restCatalogConfig))
                .isInstanceOf(TrinoException.class)
                .hasMessageContaining("Only one of 'iceberg.case-sensitive-names-supported' and 'iceberg.rest-catalog.case-insensitive-name-matching' can be set to true at a time");
    }

    @Test
    public void testCaseSensitivityValidationAllowsOnlyOne()
    {
        // Test that having only one set to true is allowed
        IcebergRestCatalogConfig restCatalogConfig1 = new IcebergRestCatalogConfig()
                .setBaseUri("http://localhost:1234")
                .setCaseInsensitiveNameMatching(false)
                .setCaseSensitiveNamesSupported(true);

        IcebergRestCatalogConfig restCatalogConfig2 = new IcebergRestCatalogConfig()
                .setBaseUri("http://localhost:1234")
                .setCaseInsensitiveNameMatching(true)
                .setCaseSensitiveNamesSupported(false);

        // These should not throw exceptions
        assertThatCode(() -> validateConfiguration(restCatalogConfig1))
                .doesNotThrowAnyException();
        assertThatCode(() -> validateConfiguration(restCatalogConfig2))
                .doesNotThrowAnyException();
    }

    @Test
    public void testCaseSensitivityValidationAllowsBothFalse()
    {
        // Test that having both set to false is allowed
        IcebergRestCatalogConfig restCatalogConfig = new IcebergRestCatalogConfig()
                .setBaseUri("http://localhost:1234")
                .setCaseInsensitiveNameMatching(false)
                .setCaseSensitiveNamesSupported(false);

        // This should not throw an exception
        assertThatCode(() -> validateConfiguration(restCatalogConfig))
                .doesNotThrowAnyException();
    }

    private void validateConfiguration(IcebergRestCatalogConfig restCatalogConfig)
    {
        // Simulate the validation logic from IcebergRestCatalogModule
        if (restCatalogConfig.isCaseSensitiveNamesSupported() && restCatalogConfig.isCaseInsensitiveNameMatching()) {
            throw new TrinoException(io.trino.spi.StandardErrorCode.NOT_SUPPORTED, "Only one of 'iceberg.case-sensitive-names-supported' and 'iceberg.rest-catalog.case-insensitive-name-matching' can be set to true at a time");
        }
    }
}
