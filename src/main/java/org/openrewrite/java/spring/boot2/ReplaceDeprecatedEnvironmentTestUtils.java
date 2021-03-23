/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.spring.boot2;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.SemanticallyEqual;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.marker.SearchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class ReplaceDeprecatedEnvironmentTestUtils extends Recipe {

    private static final ThreadLocal<JavaParser> JAVA_PARSER = ThreadLocal.withInitial(() ->
            JavaParser.fromJavaVersion()
                    .dependsOn(Collections.singletonList(Parser.Input.fromResource("/TestPropertyValues.java")))
                    .build()
    );

    public static final String ENV_UTILS_ADD_ENV_FQN = "org.springframework.boot.test.util.EnvironmentTestUtils.addEnvironment";
    public static final String TEST_PROPERTY_VALUES_FQN = "org.springframework.boot.test.util.TestPropertyValues";
    public static final MethodMatcher METHOD_MATCHER = new MethodMatcher("org.springframework.boot.test.util.EnvironmentTestUtils addEnvironment(..)");

    @Override
    public String getDisplayName() {
        return "Replace EnvironmentUtils with TestPropertyValues";
    }

    @Override
    public String getDescription() {
        return "Replaces any references to the deprecated org.springframework.boot.test.util.EnvironmentTestUtils" +
                " with org.springframework.boot.test.util.TestPropertyValues and the appropriate functionality";
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new FindEnvironmentTestUtilsVisitor();
    }

    private static class ReplaceEnvironmentUtilsMarker implements SearchResult {
        private final String templateString;
        private final List<Expression> parameters;

        private ReplaceEnvironmentUtilsMarker(String templateString, List<Expression> parameters) {
            this.templateString = templateString;
            this.parameters = parameters;
        }


        @Override
        public @Nullable String getDescription() {
            return templateString;
        }
    }

    private static class FindEnvironmentTestUtilsVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext executionContext) {
            J.MethodDeclaration m = super.visitMethodDeclaration(method, executionContext);

            if (m.getBody() == null || m.getBody().getStatements().size() == 0) {
                return m;
            }

            List<Statement> statements = m.getBody().getStatements();
            List<Statement> newStatements = new ArrayList<>();
            List<J.MethodInvocation> collectedEnvironmentMethods = new ArrayList<>();
            boolean requiresRemoval = false;

            for (Statement statement : statements) {
                if (statement instanceof J.MethodInvocation && isAddEnvironmentMethod((J.MethodInvocation) statement)) {
                    J.MethodInvocation methodInvocation = (J.MethodInvocation) statement;
                    if (collectedEnvironmentMethods.isEmpty() || isCollectedContextOrEnvironment(collectedEnvironmentMethods, methodInvocation)) {
                        collectedEnvironmentMethods.add(methodInvocation);
                        requiresRemoval = true;
                    } else {
                        newStatements.add(coalesceToFluentMethod(collectedEnvironmentMethods));
                        collectedEnvironmentMethods = new ArrayList<>();
                        collectedEnvironmentMethods.add(methodInvocation);
                    }
                } else {
                    if (!collectedEnvironmentMethods.isEmpty()) {
                        newStatements.add(coalesceToFluentMethod(collectedEnvironmentMethods));
                        collectedEnvironmentMethods = new ArrayList<>();
                    }
                    newStatements.add(statement);
                }
            }

            if (!collectedEnvironmentMethods.isEmpty()) {
                newStatements.add(coalesceToFluentMethod(collectedEnvironmentMethods));
            }

            if (requiresRemoval) {
                doAfterVisit(new ReplaceDeprecatedEnvironmentTestUtils.RemoveEnvironmentTestUtilsVisitor());
            }

            return m.withBody(m.getBody().withStatements(newStatements));
        }

        private boolean isCollectedContextOrEnvironment(List<J.MethodInvocation> collectedMethods, J.MethodInvocation methodInvocation) {
            if (methodInvocation.getArguments().size() == 0
                    || collectedMethods.size() == 0
                    || collectedMethods.get(0).getArguments().size() == 0) {
                return false;
            }
            J.MethodInvocation collectedMethod = collectedMethods.get(0);
            Expression contextOrEnvironmentToCheck = getContextOrEnvironmentArgument(methodInvocation);
            Expression collectedContextOrEnvironment = getContextOrEnvironmentArgument(collectedMethod);

            return SemanticallyEqual.areEqual(contextOrEnvironmentToCheck, collectedContextOrEnvironment);
        }

        private Expression getPairArgument(J.MethodInvocation methodInvocation) {
            if (methodInvocation.getArguments().size() < 2) {
                throw new IllegalArgumentException("getPairArgument requires a method with at least 2 arguments");
            }
            // for one variant of addEnvironment there are 3 arguments, the others have 2
            return methodInvocation.getArguments().get(methodInvocation.getArguments().size() == 3 ? 2 : 1);
        }

        private Expression getContextOrEnvironmentArgument(J.MethodInvocation methodInvocation) {
            if (methodInvocation.getArguments().size() < 2) {
                throw new IllegalArgumentException("getContextOrEnvironmentArgument requires a method with at least 2 arguments");
            }
            // for one variant of addEnvironment there are 3 arguments, the others have 2
            return methodInvocation.getArguments().get(methodInvocation.getArguments().size() == 3 ? 1 : 0);
        }

        private J.MethodInvocation coalesceToFluentMethod(List<J.MethodInvocation> collectedMethods) {
            if (collectedMethods.size() == 0) {
                throw new IllegalArgumentException("collectedMethods must have at least one element");
            }
            J.MethodInvocation toReplace = collectedMethods.get(0);

            String currentTemplateString = generateTemplateString(collectedMethods);
            List<Expression> parameters = generateParameters(collectedMethods);

            return toReplace.withMarker(new ReplaceEnvironmentUtilsMarker(currentTemplateString, parameters));
        }

        private List<Expression> generateParameters(List<J.MethodInvocation> collectedMethods) {
            if (collectedMethods.size() == 0) {
                throw new IllegalArgumentException("collectedMethods must have at least one element");
            }
            List<Expression> parameters = new ArrayList<>();
            for (J.MethodInvocation collectedMethod : collectedMethods) {
                parameters.add(getPairArgument(collectedMethod));
            }
            parameters.add(getContextOrEnvironmentArgument(collectedMethods.get(0)));

            return parameters;
        }

        private String generateTemplateString(List<J.MethodInvocation> collectedMethods) {
            StringBuilder template = new StringBuilder("TestPropertyValues.of(#{})");
            for (int i = 1; i < collectedMethods.size(); i++) {
                template.append(".and(#{})");
            }
            template.append(".applyTo(#{})");
            return template.toString();
        }

        private boolean isAddEnvironmentMethod(J.MethodInvocation method) {
            return METHOD_MATCHER.matches(method);
        }
    }

    private static class RemoveEnvironmentTestUtilsVisitor extends JavaIsoVisitor<ExecutionContext> {
        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext executionContext) {
            J.MethodInvocation m = super.visitMethodInvocation(method, executionContext);
            Optional<ReplaceEnvironmentUtilsMarker> maybeMarker = m.getMarkers().findFirst(ReplaceEnvironmentUtilsMarker.class);
            if (maybeMarker.isPresent()) {
                ReplaceEnvironmentUtilsMarker marker = maybeMarker.get();
                m = m.withTemplate(
                    template(marker.templateString)
                        .javaParser(JAVA_PARSER.get())
                        .imports(TEST_PROPERTY_VALUES_FQN)
                        .build(),
                    m.getCoordinates().replace(),
                    marker.parameters.toArray()
                );

                maybeRemoveImport(ENV_UTILS_ADD_ENV_FQN);
                maybeAddImport(TEST_PROPERTY_VALUES_FQN);
            }
            return m;
        }
    }
}