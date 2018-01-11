package org.snomed.otf.owltoolkit.conversion;

import org.semanticweb.owlapi.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.otf.owltoolkit.constants.Concepts;
import org.snomed.otf.owltoolkit.domain.AxiomRepresentation;
import org.snomed.otf.owltoolkit.domain.Relationship;
import org.snomed.otf.owltoolkit.ontology.OntologyHelper;
import org.snomed.otf.owltoolkit.ontology.OntologyService;
import org.snomed.otf.owltoolkit.taxonomy.SnomedTaxonomyLoader;

import java.util.*;

public class ConversionService {

	public static final String ROLE_GROUP_URI = OntologyService.SNOMED_IRI + OntologyService.ROLE_GROUP;

	private final SnomedTaxonomyLoader snomedTaxonomyLoader;
	private final OntologyService ontologyService;

	private static final Logger LOGGER = LoggerFactory.getLogger(ConversionService.class);

	public ConversionService(Set<Long> ungroupedAttributes) {
		snomedTaxonomyLoader = new SnomedTaxonomyLoader();
		ontologyService = new OntologyService(ungroupedAttributes);
	}

	/**
	 * Converts an OWL Axiom expression String to an AxiomRepresentation containing a concept id or set of relationships for each side of the expression.
	 * Currently supported axiom types are SubClassOf and EquivalentClasses.
	 *
	 * @param axiomExpression The Axiom expression to convert.
	 * @return AxiomRepresentation with the details of the expression or null if the axiom type is not supported.
	 * @throws ConversionException if the Axiom expression is malformed or of an unexpected structure.
	 */
	public AxiomRepresentation convertAxiomToRelationships(String axiomExpression) throws ConversionException {
		return convertAxiomToRelationships(null, axiomExpression);
	}

	/**
	 * Converts an OWL Axiom expression String to an AxiomRepresentation containing a concept id or set of relationships for each side of the expression.
	 * Currently supported axiom types are SubClassOf and EquivalentClasses.
	 *
	 * @param referencedComponentId Specifying a referencedComponentId will force the other side of the axiom to be returned as relationships even if only a single named concept is on that side.
	 * @param axiomExpression The Axiom expression to convert.
	 * @return AxiomRepresentation with the details of the expression or null if the axiom type is not supported.
	 * @throws ConversionException if the Axiom expression is malformed or of an unexpected structure.
	 */
	public AxiomRepresentation convertAxiomToRelationships(Long referencedComponentId, String axiomExpression) throws ConversionException {
		OWLAxiom owlAxiom;
		try {
			owlAxiom = snomedTaxonomyLoader.deserialiseAxiom(axiomExpression);
		} catch (OWLOntologyCreationException e) {
			throw new ConversionException("Failed to deserialise axiom expression '" + axiomExpression + "'.");
		}

		AxiomType<?> axiomType = owlAxiom.getAxiomType();

		if (axiomType != AxiomType.SUBCLASS_OF && axiomType != AxiomType.EQUIVALENT_CLASSES) {
			LOGGER.info("Only SubClassOf and EquivalentClasses can be converted to relationships. " +
					"Axiom given is of type " + axiomType.getName() + ". Returning null.");
			return null;
		}

		AxiomRepresentation representation = new AxiomRepresentation();
		OWLClassExpression leftHandExpression;
		OWLClassExpression rightHandExpression;
		if (axiomType == AxiomType.EQUIVALENT_CLASSES) {
			OWLEquivalentClassesAxiom equivalentClassesAxiom = (OWLEquivalentClassesAxiom) owlAxiom;
			Set<OWLClassExpression> classExpressions = equivalentClassesAxiom.getClassExpressions();
			if (classExpressions.size() != 2) {
				throw new ConversionException("Expecting EquivalentClasses expression to contain 2 class expressions, got " + classExpressions.size() + " - axiom '" + axiomExpression + "'.");
			}
			Iterator<OWLClassExpression> iterator = classExpressions.iterator();
			leftHandExpression = iterator.next();
			rightHandExpression = iterator.next();
		} else {
			representation.setPrimitive(true);

			OWLSubClassOfAxiom subClassOfAxiom = (OWLSubClassOfAxiom) owlAxiom;
			leftHandExpression = subClassOfAxiom.getSubClass();
			rightHandExpression = subClassOfAxiom.getSuperClass();
		}

		Long leftNamedClass = getNamedClass(axiomExpression, leftHandExpression, "left");
		if (leftNamedClass != null) {
			if (referencedComponentId != null && !referencedComponentId.equals(leftNamedClass)) {
				// Force the named concept which is not the referencedComponentId to be returned as a set of relationships.
				Map<Integer, List<Relationship>> relationships = new HashMap<>();
				relationships.put(0, Collections.singletonList(new Relationship(0, Concepts.IS_A_LONG, leftNamedClass)));
				representation.setLeftHandSideRelationships(relationships);
			} else {
				representation.setLeftHandSideNamedConcept(leftNamedClass);
			}
		} else {
			// If not a named class it must be an expression which can be converted to a set of relationships
			representation.setLeftHandSideRelationships(getRelationships(leftHandExpression, "left", axiomExpression));
		}

		Long rightNamedClass = getNamedClass(axiomExpression, rightHandExpression, "right");
		if (rightNamedClass != null) {
			if (referencedComponentId != null && !referencedComponentId.equals(rightNamedClass)) {
				// Force the named concept which is not the referencedComponentId to be returned as a set of relationships.
				Map<Integer, List<Relationship>> relationships = new HashMap<>();
				relationships.put(0, Collections.singletonList(new Relationship(0, Concepts.IS_A_LONG, rightNamedClass)));
				representation.setRightHandSideRelationships(relationships);
			} else {
				representation.setRightHandSideNamedConcept(rightNamedClass);
			}
		} else {
			// If not a named class it must be an expression which can be converted to a set of relationships
			representation.setRightHandSideRelationships(getRelationships(rightHandExpression, "right", axiomExpression));
		}

		return representation;
	}

	public String convertRelationshipsToAxiom(AxiomRepresentation representation) {
		OWLClassAxiom owlClassAxiom = ontologyService.createOwlClassAxiom(representation);
		return owlClassAxiom.toString();
	}

	private Long getNamedClass(String axiomExpression, OWLClassExpression owlClassExpression, String side) throws ConversionException {
		if (owlClassExpression.getClassExpressionType() != ClassExpressionType.OWL_CLASS) {
			return null;
		}
		Set<OWLClass> classesInSignature = owlClassExpression.getClassesInSignature();
		if (classesInSignature.size() > 1) {
			throw new ConversionException("Expecting a maximum of 1 class in " + side + " hand side of axiom, got " + classesInSignature.size() + " - axiom '" + axiomExpression + "'.");
		}

		if (classesInSignature.size() == 1) {
			OWLClass namedClass = classesInSignature.iterator().next();
			return OntologyHelper.getConceptId(namedClass);
		}
		return null;
	}

	private Map<Integer, List<Relationship>> getRelationships(OWLClassExpression owlClassExpression, String side, String wholeAxiomExpression) throws ConversionException {

		Map<Integer, List<Relationship>> relationshipGroups = new HashMap<>();

		List<OWLClassExpression> expressions;
		if (owlClassExpression.getClassExpressionType() == ClassExpressionType.OBJECT_INTERSECTION_OF) {
			OWLObjectIntersectionOf intersectionOf = (OWLObjectIntersectionOf) owlClassExpression;
			expressions = intersectionOf.getOperandsAsList();
		} else {
			throw new ConversionException("Expecting ObjectIntersectionOf at first level of " + side + " hand side of axiom, got " + owlClassExpression.getClassExpressionType() + " - axiom '" + wholeAxiomExpression + "'.");
		}

		int rollingGroupNumber = 0;
		for (OWLClassExpression operand : expressions) {
			ClassExpressionType operandClassExpressionType = operand.getClassExpressionType();
			if (operandClassExpressionType == ClassExpressionType.OWL_CLASS) {
				// Is-a relationship
				relationshipGroups.computeIfAbsent(0, key -> new ArrayList<>()).add(new Relationship(0, Concepts.IS_A_LONG, OntologyHelper.getConceptId(operand.asOWLClass())));

			} else if (operandClassExpressionType == ClassExpressionType.OBJECT_SOME_VALUES_FROM) {
				// Either start of attribute or role group
				OWLObjectSomeValuesFrom someValuesFrom = (OWLObjectSomeValuesFrom) operand;
				OWLObjectPropertyExpression property = someValuesFrom.getProperty();
				if (isRoleGroup(property)) {
					rollingGroupNumber++;
					// Extract Group
					OWLClassExpression filler = someValuesFrom.getFiller();
					if (filler.getClassExpressionType() == ClassExpressionType.OBJECT_SOME_VALUES_FROM) {
						Relationship relationship = extractRelationship((OWLObjectSomeValuesFrom) filler, rollingGroupNumber);
						relationshipGroups.computeIfAbsent(rollingGroupNumber, key -> new ArrayList<>()).add(relationship);
					} else if (filler.getClassExpressionType() == ClassExpressionType.OBJECT_INTERSECTION_OF) {
						OWLObjectIntersectionOf listOfAttributes = (OWLObjectIntersectionOf) filler;
						for (OWLClassExpression classExpression : listOfAttributes.getOperandsAsList()) {
							if (classExpression.getClassExpressionType() == ClassExpressionType.OBJECT_SOME_VALUES_FROM) {
								Relationship relationship = extractRelationship((OWLObjectSomeValuesFrom) classExpression, rollingGroupNumber);
								relationshipGroups.computeIfAbsent(rollingGroupNumber, key -> new ArrayList<>()).add(relationship);
							} else {
								throw new ConversionException("Expecting ObjectSomeValuesFrom within ObjectIntersectionOf as part of role group, got " + classExpression.getClassExpressionType() + " - axiom '" + wholeAxiomExpression + "'.");
							}
						}
					} else {
						throw new ConversionException("Expecting ObjectSomeValuesFrom with role group to have a value of ObjectSomeValuesFrom, got " + filler.getClassExpressionType() + " - axiom '" + wholeAxiomExpression + "'.");
					}
				} else {
					Relationship relationship = extractRelationship(someValuesFrom, 0);
					relationshipGroups.computeIfAbsent(0, key -> new ArrayList<>()).add(relationship);
				}

			} else {
				throw new ConversionException("Expecting Class or ObjectSomeValuesFrom at second level of " + side + " hand side of axiom, got " + operandClassExpressionType + " - axiom '" + wholeAxiomExpression + "'.");
			}
		}

		return relationshipGroups;
	}

	private Relationship extractRelationship(OWLObjectSomeValuesFrom someValuesFrom, int groupNumber) throws ConversionException {
		OWLObjectPropertyExpression property = someValuesFrom.getProperty();
		OWLObjectProperty namedProperty = property.getNamedProperty();
		long type = OntologyHelper.getConceptId(namedProperty);

		OWLClassExpression filler = someValuesFrom.getFiller();
		ClassExpressionType classExpressionType = filler.getClassExpressionType();
		if (classExpressionType != ClassExpressionType.OWL_CLASS) {
			throw new ConversionException("Expecting right hand side of ObjectSomeValuesFrom to be type Class, got " + classExpressionType + ".");
		}
		long value = OntologyHelper.getConceptId(filler.asOWLClass());

		return new Relationship(groupNumber, type, value);
	}

	private boolean isRoleGroup(OWLObjectPropertyExpression expression) {
		OWLObjectProperty namedProperty = expression.getNamedProperty();
		return ROLE_GROUP_URI.equals(namedProperty.getIRI().toString());
	}

}
