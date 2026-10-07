package com.portalstream.app.network

sealed class PortalException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class UserAgentBlocked(
        message: String = "User-Agent non autorizzato o bloccato dal server. Modificalo nelle impostazioni del portale."
    ) : PortalException(message)

    class MaxConnectionsReached(
        message: String = "Troppi utenti connessi. La playlist è attualmente in uso su un altro dispositivo."
    ) : PortalException(message)

    class ServerUnreachable(
        message: String = "Impossibile raggiungere il server. Verifica l'URL, il DNS o la connessione di rete."
    ) : PortalException(message)

    class InvalidCredentials(
        message: String = "Credenziali non valide o MAC Address non registrato."
    ) : PortalException(message)
}
