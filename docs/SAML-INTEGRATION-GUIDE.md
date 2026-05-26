# SAML 2.0 SSO Integration Guide

Sber DLMM Platform — Corporate Identity Provider Integration  
Target IdPs: Azure AD (Microsoft Entra ID) and Sber Federation  
Sprint: 15 full wiring (scaffold shipped Sprint 14)

---

## 1. Prerequisites

### Backend changes (Sprint 15)

Add the Spring Security SAML2 starter to `dlmm-user-service/pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-saml2-service-provider</artifactId>
</dependency>
```

Configure a `RelyingPartyRegistration` bean in `SecurityConfig` pointing at the IdP metadata URL.

### Required config properties

Add to `dlmm-user-service/src/main/resources/application.yml`:

```yaml
dlmm:
  saml:
    idp-metadata-url: ""        # Set via IDP_METADATA_URL env var
    sp-entity-id: "https://dlmm.sber-online.ru/saml/sp"
    acs-url: "https://dlmm.sber-online.ru/api/v1/auth/saml/callback"
```

Set `IDP_METADATA_URL` in `docker/.env` (never commit real URLs to VCS).

---

## 2. Azure AD Setup

### App Registration

1. Azure Portal > Azure Active Directory > App registrations > New registration
2. Name: `Sber DLMM Platform`
3. Supported account types: Accounts in this organizational directory only
4. Redirect URI: leave blank (SAML uses POST binding, not OAuth redirect)

### SAML Configuration

1. App registration > Enterprise applications > find `Sber DLMM Platform`
2. Single sign-on > SAML
3. Basic SAML Configuration:
   - Identifier (Entity ID): `https://dlmm.sber-online.ru/saml/sp`
   - Reply URL (ACS): `https://dlmm.sber-online.ru/api/v1/auth/saml/callback`
   - Sign-on URL: `https://dlmm.sber-online.ru/api/v1/auth/saml/initiate`
4. Save — Azure generates IdP metadata URL in the form:
   `https://login.microsoftonline.com/<tenant-id>/federationmetadata/2007-06/federationmetadata.xml`

### Claim Mapping (email → DLMM role)

Add attribute claims under "Attributes & Claims":

| Claim name | Value |
|---|---|
| `email` | `user.mail` |
| `displayName` | `user.displayname` |
| `dlmmRole` | static value `USER` (or use group-based mapping for ADMIN) |

In `SamlAuthController.handleCallback()` (Sprint 15): extract these claims from the `Saml2AuthenticatedPrincipal`, look up or create the user, and issue a platform JWT with the mapped role.

---

## 3. Sber Federation Setup

Contact `dlmm-infra@sber.ru` with the following SP metadata URL:

```
https://dlmm.sber-online.ru/api/v1/auth/saml/metadata
```

The ops team will register the SP and provide:
- IdP metadata URL (Sber Federation endpoint)
- Any required claim mapping documentation

Configuration follows the same pattern as Azure AD above; only the IdP metadata URL differs.

---

## 4. Local Testing with Keycloak

For development/QA without access to a corporate IdP:

```bash
# Start Keycloak alongside the DLMM stack
docker run -p 8090:8080 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:24.0 start-dev
```

1. Open `http://localhost:8090/admin`, log in as admin/admin
2. Create realm `dlmm-dev`
3. Create client: Client ID `sber-dlmm-sp`, Protocol `saml`
4. Set Valid Redirect URIs to `http://localhost:8081/api/v1/auth/saml/callback`
5. Set IDP-Initiated SSO URL name to `sber-dlmm`
6. Export realm metadata from:
   `http://localhost:8090/realms/dlmm-dev/protocol/saml/descriptor`
7. Set `IDP_METADATA_URL` to the URL above in `docker/.env`

Create a test user in Keycloak with a `dlmmRole` attribute set to `USER`.

---

## 5. Production Checklist

Before enabling SAML SSO in production:

- [ ] `spring-security-saml2-service-provider` wired in `pom.xml`
- [ ] `RelyingPartyRegistration` bean configured with IdP metadata URL
- [ ] `SamlAuthController.handleCallback()` validates assertion and issues JWT
- [ ] `IDP_METADATA_URL` set in production secrets manager (not `.env`)
- [ ] SP metadata registered with Azure AD and/or Sber Federation
- [ ] HTTPS enforced end-to-end (ACS URL must be `https://`)
- [ ] Claim mapping tested: corporate email maps to correct DLMM user / role
- [ ] Session fixation protection reviewed (Spring SAML2 handles this by default)
- [ ] `WantAssertionsSigned=true` verified in SP metadata — IdP must sign assertions
- [ ] Load test the callback endpoint under peak login concurrency
- [ ] Rollback plan: disable SAML button via feature flag without code deploy

---

## Current Scaffold State (Sprint 14)

| Endpoint | Status |
|---|---|
| `GET /api/v1/auth/saml/metadata` | Returns static placeholder XML |
| `GET /api/v1/auth/saml/initiate` | Returns 501 NOT_IMPLEMENTED |
| `POST /api/v1/auth/saml/callback` | Returns 501 NOT_IMPLEMENTED |

The frontend login button (`Войти через корпоративный SSO`) redirects to `/api/v1/auth/saml/initiate` and the callback page at `/saml/callback` surfaces the 501 gracefully.

Full wiring is Sprint 15 work. See `SPRINT-PLAN.md` for task breakdown.
