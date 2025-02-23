FROM docker-private.infra.cloudera.com/cloudera_base/ubi8/cldr-openjdk-11-runtime-cis:1.15-1-cis-22032023
# We can not use alpine based image because of https://github.com/grpc/grpc-java/issues/8751
MAINTAINER info@cloudera.com

# REPO URL to download jar
ARG REPO_URL=https://repo.hortonworks.com/content/repositories/releases
ARG VERSION=''

ENV VERSION ${VERSION}
ENV PROMETHEUS_VERSION 0.17.2

WORKDIR /

RUN microdnf install unzip

# install the environment app
ADD ${REPO_URL}/com/sequenceiq/environment/$VERSION/environment-$VERSION.jar /environment.jar

# add jmx exporter
RUN curl -o /jmx_prometheus_javaagent.jar https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/$PROMETHEUS_VERSION/jmx_prometheus_javaagent-$PROMETHEUS_VERSION.jar

# extract schema files
RUN ( unzip environment.jar schema/* -d / ) || \
    ( unzip environment.jar BOOT-INF/classes/schema/* -d /tmp/ && mv /tmp/BOOT-INF/classes/schema/ /schema/ )

# Install starter script for the Environment application
COPY bootstrap/start_environment_app.sh /
COPY bootstrap/wait_for_environment_api.sh /

# Add OpenSSL provider to Java security policy file (FIPS enabled mode only).
# - insert a line: fips.provider.1=OpenSSL
# - increase the index for other fips.provider lines.
RUN JAVA_SECURITY_POLICY_FILE=$JAVA_HOME/conf/security/java.security \
&& for I in {20..1}; do J=$((I+1)); sed -i "s/fips.provider.$I=/fips.provider.$J=/" $JAVA_SECURITY_POLICY_FILE; done \
&& N=$(grep -m 1 -n "^fips\.provider\.[[:digit:]]=" $JAVA_SECURITY_POLICY_FILE | cut -d: -f1) \
&& sed -i "$N i fips.provider.1=OpenSSL" $JAVA_SECURITY_POLICY_FILE

RUN JAVA_SECURITY_POLICY_FILE=$JAVA_HOME/conf/security/java.security \
&& for I in {20..1}; do J=$((I+1)); sed -i "s/security.provider.$I=/security.provider.$J=/" $JAVA_SECURITY_POLICY_FILE; done \
&& N=$(grep -m 1 -n "^security\.provider\.[[:digit:]]=" $JAVA_SECURITY_POLICY_FILE | cut -d: -f1) \
&& sed -i "$N i security.provider.1=OpenSSL" $JAVA_SECURITY_POLICY_FILE

ENTRYPOINT ["/start_environment_app.sh"]
