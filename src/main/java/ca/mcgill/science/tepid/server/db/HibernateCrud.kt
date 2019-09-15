package ca.mcgill.science.tepid.server.db

import ca.mcgill.science.tepid.models.bindings.TepidDb
import ca.mcgill.science.tepid.server.util.text
import ca.mcgill.science.tepid.utils.Loggable
import ca.mcgill.science.tepid.utils.WithLogging
import java.util.*
import javax.persistence.EntityExistsException
import javax.persistence.EntityManager
import javax.persistence.EntityManagerFactory
import javax.persistence.EntityNotFoundException
import javax.persistence.NoResultException
import javax.ws.rs.core.Response

interface IHibernateCrud<T : Any, P> : Loggable {

    fun create(obj: T): T

    fun read(id: P): T?

    fun readAll(): List<T>

    fun update(obj: T): T

    fun delete(obj: T)

    fun deleteById(id: P)

    fun updateOrCreateIfNotExist(obj: T): T
}

class HibernateCrud<T : TepidDb, P>(val emf: EntityManagerFactory, val classParameter: Class<T>) : IHibernateCrud<T, P>,
    Loggable by WithLogging() {

    fun <T> dbOp(errorLogger: (e: Exception) -> String = { e -> "DB error: $e" }, f: (em: EntityManager) -> T): T {
        val em = emf.createEntityManager()
        try {
            return f(em)
        } catch (e: Exception) {
            e.printStackTrace()
            log.error(errorLogger(e))
            throw e
        } finally {
            em.close()
        }
    }

    fun <T> dbOpTransaction(
        errorLogger: (e: Exception) -> String = { e -> "DB error: $e" },
        f: (em: EntityManager) -> T
    ): T {
        return dbOp(errorLogger) {
            try {
                it.transaction.begin()
                val o = f(it)
                it.transaction.commit()
                return@dbOp o
            } catch (e: Exception) {
                log.error(errorLogger(e))
                e.printStackTrace()
                it.transaction.rollback()
                throw e
            }
        }
    }

    override fun create(obj: T): T {
        return dbOpTransaction(
            { e -> "Error inserting object {\"object\":\"$obj\", \"error\":\"$e\"" },
            { em -> em.merge(obj) })
    }

    override fun read(id: P): T? {
        return try {
            dbOp(
                { e -> "Error reading object {\"class\":\"$classParameter\",\"id\":\"$id\", \"error\":\"$e\"" },
                { em -> em.find(classParameter, id) })
        } catch (e: NoResultException) {
            null
        }
    }

    override fun readAll(): List<T> {
        return dbOp(
            { e -> "Error reading all objects {\"class\":\"$classParameter\", \"error\":\"$e\"" },
            { em ->
                em.createQuery("SELECT c FROM ${classParameter.simpleName} c", classParameter).resultList
                    ?: emptyList()
            }
        )
    }

    override fun update(obj: T): T {
        return dbOpTransaction(
            { e -> "Error updating object {\"object\":\"$obj\", \"error\":\"$e\"" },
            { em -> em.merge(obj); return@dbOpTransaction obj })
    }

    override fun delete(obj: T) {

        dbOpTransaction({ e ->
            "Error deleting object {\"object\":\"$obj\", \"error\":\"$e\"}"
        },
            {
                it.remove(if (it.contains(obj)) obj else it.merge(obj))
            })
    }

    override fun deleteById(id: P) {
        val em = emf.createEntityManager()
        try {
            val u = em.find(classParameter, id)
            delete(u)
        } finally {
            em.close()
        }
    }

    override fun updateOrCreateIfNotExist(obj: T): T {
        obj._id
            ?: return run { obj._id = UUID.randomUUID().toString(); create(obj) } // has no ID, needs to be created
        val em = emf.createEntityManager()
        try {
            em.getReference(classParameter, obj._id)
            return dbOpTransaction({ e -> "Error putting modifications {\"object\":\"$obj\", \"error\":\"$e\"}" }) { t ->
                t.merge(obj)
            }
        } catch (e: EntityNotFoundException) {
            return (create(obj)) // has ID, ID not in DB
        } finally {
            em.close()
        }
    }
}

fun parsePersistenceErrorToResponse(e: Exception): Response {
    return when (e) {
        is EntityNotFoundException -> Response.Status.NOT_FOUND.text("Not found")
        is IllegalArgumentException -> Response.Status.BAD_REQUEST.text("${e::class.java.simpleName} occurred")
        is EntityExistsException -> Response.Status.CONFLICT.text("Entity Exists; ${e::class.java.simpleName} occurred")
        else -> Response.Status.INTERNAL_SERVER_ERROR.text("Ouch! ${e::class.java.simpleName} occurred")
    }
}
