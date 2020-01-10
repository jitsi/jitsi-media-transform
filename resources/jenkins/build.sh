#!/bin/bash

set -e

mvn clean verify package
mvn antrun:run@ktlint
