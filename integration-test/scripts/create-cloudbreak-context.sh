#!/usr/bin/env bash

: ${INTEGCB_LOCATION?"integcb location"}

date
echo -e "\n\033[1;96m--- Create cloudbreak context\033[0m\n"
cp $INTEGCB_LOCATION/Profile_template $INTEGCB_LOCATION/Profile
cp docker-compose_template.yml docker-compose.yml
./scripts/fill_public_ip.sh
echo -e "\n" >> integcb/Profile
echo "export VAULT_AUTO_UNSEAL=true" >> integcb/Profile
echo "export VAULT_DB_SCHEMA=inet_vault_$(date +%s)" >> integcb/Profile
echo "export CB_UI_MAX_WAIT=600" >> integcb/Profile
echo "export CB_JAVA_OPTS=-Dcb.statuschecker.skip.window.minutes=0"
if [[ "$AWS" == true ]]; then
    echo "export AWS_ACCESS_KEY_ID=${INTEGRATIONTEST_AWS_CREDENTIAL_ACCESSKEYID}" >> integcb/Profile
    echo "export AWS_SECRET_ACCESS_KEY=${INTEGRATIONTEST_AWS_CREDENTIAL_SECRETKEY}" >> integcb/Profile
    echo "export CB_AWS_ACCOUNT_ID=${INTEGRATIONTEST_AWS_ACCOUNTID}" >> integcb/Profile
fi
if [[ ! -z "${CUSTOM_UMS_SHORT_TIMEOUT}" ]]; then
  echo "export ALTUS_UMS_CLIENT_GRPC_SHORT_TIMEOUT_SEC=${CUSTOM_UMS_SHORT_TIMEOUT}" >> integcb/Profile
fi
