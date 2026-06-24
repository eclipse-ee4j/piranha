# Contributing

How can I contribute to Piranha? Well it all depends on the level of 
involvement you are aiming for. The following comes to mind:

1. Write blog entries with example applications
2. File issues for bugs you encounter.
3. Answer questions that people file at the issue tracker.
4. Fork the repository and issue pull requests.

Which ever level you pick we welcome you!

## Eclipse Contributor Agreement

Before your contribution can be accepted by the project team, contributors must
electronically sign the Eclipse Contributor Agreement (ECA).

* http://www.eclipse.org/legal/ECA.php

Git commit records are required to take a specific form.
The credentials of the actual author must be used to populate the Author field.
The author credentials must specify the author’s actual (legal) name and email address.
The email address used must match the email address that the Eclipse Foundation has on file for the author (case-sensitive).

For more information, please see the Eclipse Committer Handbook:
https://www.eclipse.org/projects/handbook/#resources-commit

## Building and testing Piranha locally

### Building Piranha and running the tests

```
mvn clean install
```

If you do not want the tests to run use:

```
mvn -DskipTests clean install 
```

### Running a singular test

To run a singular test pass in `-Dtest=expression`, see the `surefire` plugin
documentation for more information.

### Running our more complex tests

Our more complex tests are in the `test` profile which we do not release as part
of a release because these modules only test functionality.

You can run our more complex tests using:

```
mvn -P test clean install
```

### Run the external tests (including TCKs)

To run all the external tests use:

```
mvn -P external clean install
```

Or go into the directory of the external test you want to run and use:

```
mvn clean install
```

For the Servlet TCK if you want to run a singular test use 
`-Drun.test=expression`. See the example below.

```
mvn -Drun.test=com/sun/ts/tests/servlet/spec/errorpage/URLClient.java#servletToDifferentErrorPagesTest verify
```

## Problems

Support for Java modules has a feature gap in Eclipse and as such a workaround
needs to be employed to make it work properly (June 2021).
