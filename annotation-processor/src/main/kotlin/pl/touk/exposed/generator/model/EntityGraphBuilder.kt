package pl.touk.exposed.generator.model

import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import pl.touk.exposed.generator.env.AnnotationEnvironment
import pl.touk.exposed.generator.env.TypeEnvironment
import pl.touk.exposed.generator.env.enclosingTypeElement
import pl.touk.exposed.generator.env.toTypeElement
import pl.touk.exposed.generator.env.toVariableElement
import pl.touk.exposed.generator.validation.EntityNotMappedException
import pl.touk.exposed.generator.validation.GeneratedValueWithoutIdException
import pl.touk.exposed.generator.validation.MissingIdException
import javax.lang.model.element.VariableElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.persistence.Column
import javax.persistence.JoinColumn
import javax.persistence.JoinTable
import javax.persistence.OneToMany
import javax.persistence.Table

class EntityGraphBuilder(
        private val typeEnv: TypeEnvironment, private val annEnv: AnnotationEnvironment
) {

    fun build(): EntityGraphs {
        val graphs = EntityGraphs()

        // TODO split
        for (entityElt in annEnv.entities) {
            val tableAnn = entityElt.getAnnotation(Table::class.java)
            val graph = graphs.getOrDefault(entityElt.packageName, EntityGraph())
            graph[entityElt] = EntityDefinition(
                    name = entityElt.simpleName, qualifiedName = entityElt.qualifiedName, table = tableAnn.name
            )
            graphs[entityElt.packageName] = graph
        }

        for (idElt in annEnv.ids) {
            val entityType = idElt.enclosingTypeElement()
            val graph = graphs[entityType.packageName] ?: throw EntityNotMappedException(entityType)
            graph.computeIfPresent(entityType) { _, entity ->
                val type = idElt.asType().getIdTypeDefinition()
                val annotation = idElt.getAnnotation(Column::class.java)
                entity.copy(id = IdDefinition(idElt.simpleName, type = type, annotation = annotation, typeMirror = idElt.asType()))
            }
        }

        for (genValueElt in annEnv.genValues) {
            val entityType = genValueElt.enclosingTypeElement()
            val graph = graphs[entityType.packageName] ?: throw EntityNotMappedException(entityType)
            graph.computeIfPresent(entityType) { _, entity ->
                val idDefinition = entity.id ?: throw GeneratedValueWithoutIdException(genValueElt, entityType)
                entity.copy(id = idDefinition.copy(generatedValue = true))
            }
        }

        for (columnElt in annEnv.columns) {
            val entityType = columnElt.enclosingTypeElement()
            val graph = graphs[entityType.packageName] ?: throw EntityNotMappedException(entityType)

            graph.computeIfPresent(entityType) { _, entity ->
                val columnAnn = columnElt.getAnnotation(Column::class.java)
                val typeMirror = entity.id?.typeMirror ?: throw MissingIdException(entity)
                val type = columnElt.asType().getTypeDefinition()

                val columnDefinition = PropertyDefinition(name = columnElt.simpleName, annotation = columnAnn,
                        type = type, typeMirror = typeMirror, nullable = isNullable(columnElt))

                entity.addProperty(columnDefinition)
            }
        }

        for (oneToMany in annEnv.oneToMany) {
            val entityType = oneToMany.enclosingTypeElement()
            val graph = graphs[entityType.packageName] ?: throw EntityNotMappedException(entityType)
            val otmAnn = oneToMany.getAnnotation(OneToMany::class.java)
            val target = oneToMany.asType().getTypeArgument().asElement().toTypeElement()

            graph.computeIfPresent(entityType) { _, entity ->
                val idType = entity.id?.type ?: throw MissingIdException(entity)
                val associationDef = AssociationDefinition(
                        name = oneToMany.simpleName, type = AssociationType.ONE_TO_MANY,
                        target = target, mappedBy = otmAnn.mappedBy, idType = idType
                )
                entity.addAssociation(associationDef)
            }
        }

        for (manyToOne in annEnv.manyToOne) {
            val entityType = manyToOne.enclosingTypeElement()
            val graph = graphs[entityType.packageName] ?: throw EntityNotMappedException(entityType)

            graph.computeIfPresent(entityType) { _, entity ->
                val join = manyToOne.getAnnotation(JoinColumn::class.java)
                val target = manyToOne.toVariableElement().asType().asDeclaredType().asElement().toTypeElement()
                val idType = entity.id?.type ?: throw MissingIdException(entity)
                val associationDef = AssociationDefinition(
                        name = manyToOne.simpleName, type = AssociationType.MANY_TO_ONE,
                        target = target, joinColumn = join.name, idType = idType
                )
                entity.addAssociation(associationDef)
            }
        }

        for (manyToMany in annEnv.manyToMany) {
            val entityType = manyToMany.enclosingTypeElement()
            val graph = graphs[entityType.packageName] ?: throw EntityNotMappedException(entityType)
            val joinTableAnn = manyToMany.getAnnotation(JoinTable::class.java)
            val target = manyToMany.asType().getTypeArgument().asElement().toTypeElement()

            graph.computeIfPresent(entityType) { _, entity ->
                val idType = entity.id?.type ?: throw MissingIdException(entity)
                val associationDef = AssociationDefinition(
                        name = manyToMany.simpleName, type = AssociationType.MANY_TO_MANY,
                        target = target, joinTable = joinTableAnn.name, idType = idType
                )
                entity.addAssociation(associationDef)
            }
        }

        // unidirectional post-process
        for (oneToMany in annEnv.oneToMany) {
            val entityType = oneToMany.enclosingTypeElement()
            val joinColumnAnn = oneToMany.getAnnotation(JoinColumn::class.java) ?: continue
            val targetType = oneToMany.asType().getTypeArgument().asElement().toTypeElement()

            val graph = graphs[targetType.packageName] ?: throw EntityNotMappedException(targetType)
            graph.computeIfPresent(targetType) { _, entity ->
                val isMapped = entity.associations.any { assoc -> assoc.type == AssociationType.MANY_TO_ONE && assoc.target == entityType }
                if (isMapped) {
                    entity
                } else {
                    val parentEntityId = graphs.entityId(entityType)
                    val associationDef = AssociationDefinition(
                            name = entityType.simpleName, type = AssociationType.MANY_TO_ONE,
                            target = entityType, joinColumn = joinColumnAnn.name, mapped = false,
                            idType = parentEntityId.type
                    )
                    entity.addAssociation(associationDef)
                }
            }
        }
        return graphs
    }

    private fun isNullable(columnElt: VariableElement) =
            columnElt.getAnnotation(NotNull::class.java) == null && columnElt.getAnnotation(Nullable::class.java) != null
    
    private fun TypeMirror.asDeclaredType(): DeclaredType {
        require(this is DeclaredType)
        return this
    }

    private fun TypeMirror.getTypeArgument(): DeclaredType {
        return this.asDeclaredType().typeArguments[0].asDeclaredType()
    }

    private fun TypeMirror.getTypeDefinition(): PropertyType {
        return when {
            isString() -> PropertyType.STRING
            isBoolean() -> PropertyType.BOOL
            isNumeric() -> PropertyType.LONG
            else -> TODO()
        }
    }

    private fun TypeMirror.getIdTypeDefinition(): IdType {
        return when {
            isUUID() -> IdType.UUID
            isString() -> IdType.STRING
            isInteger() -> IdType.INTEGER
            isShort() -> IdType.SHORT
            isNumeric() -> IdType.LONG
            else -> TODO()
        }
    }

    private fun TypeMirror.isString() = typeEnv.isSameType(this, "java.lang.String")

    private fun TypeMirror.isInteger() = typeEnv.isSameType(this, "java.lang.Integer")

    private fun TypeMirror.isShort() = typeEnv.isSameType(this, "java.lang.Short")

    private fun TypeMirror.isBoolean() = typeEnv.isSameType(this, "java.lang.Boolean") || kind == TypeKind.BOOLEAN

    private fun TypeMirror.isUUID() = typeEnv.isSameType(this, "java.util.UUID")

    // TODO float/int/long/double
    private fun TypeMirror.isNumeric() = typeEnv.isSubType(this, "java.lang.Number") ||
            kind in listOf(TypeKind.LONG, TypeKind.INT, TypeKind.DOUBLE, TypeKind.FLOAT, TypeKind.SHORT)

}
