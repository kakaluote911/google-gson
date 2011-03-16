/*
 * Copyright (C) 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.gson;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Type;

/**
 * Provides ability to apply a visitor to an object and all of its fields
 * recursively.
 * 
 * @author Inderjeet Singh
 * @author Joel Leitch
 */
final class ObjectNavigator {

  public interface Visitor {
    public void start(ObjectTypePair node);

    public void end(ObjectTypePair node);

    /**
     * This is called before the object navigator starts visiting the current
     * object
     */
    void startVisitingObject(Object node);

    /**
     * This is called to visit the current object if it is an array
     */
    void visitArray(Object array, Type componentType);

    /**
     * This is called to visit an object field of the current object
     */
    void visitObjectField(FieldAttributes f, Type typeOfF, Object obj);

    /**
     * This is called to visit an array field of the current object
     */
    void visitArrayField(FieldAttributes f, Type typeOfF, Object obj);

    /**
     * This is called to visit an object using a custom handler
     * 
     * @return true if a custom handler exists, false otherwise
     */
    public boolean visitUsingCustomHandler(ObjectTypePair objTypePair);

    /**
     * This is called to visit a field of the current object using a custom
     * handler
     */
    public boolean visitFieldUsingCustomHandler(FieldAttributes f, Type actualTypeOfField,
        Object parent);

    void visitPrimitive(Object primitive);
    
    /**
     * Retrieve the current target
     */
    Object getTarget();
  }

  private final ExclusionStrategy exclusionStrategy;
  private final ObjectTypePair objTypePair;

  /**
   * @param objTypePair
   *          The object,type (fully genericized) being navigated
   * @param exclusionStrategy
   *          the concrete strategy object to be used to filter out fields of an
   *          object.
   */
  ObjectNavigator(ObjectTypePair objTypePair, ExclusionStrategy exclusionStrategy) {
    this.objTypePair = objTypePair;
    this.exclusionStrategy = Preconditions.checkNotNull(exclusionStrategy);
  }

  /**
   * Navigate all the fields of the specified object. If a field is null, it
   * does not get visited.
   */
  public void accept(Visitor visitor) {
    if (exclusionStrategy.shouldSkipClass(Types.getRawType(objTypePair.type))) {
      return;
    }
    boolean visitedWithCustomHandler = visitor.visitUsingCustomHandler(objTypePair);
    if (!visitedWithCustomHandler) {
      Object obj = objTypePair.getObject();
      Object objectToVisit = (obj == null) ? visitor.getTarget() : obj;
      if (objectToVisit == null) {
        return;
      }
      objTypePair.setObject(objectToVisit);
      visitor.start(objTypePair);
      try {
        if (Types.isArray(objTypePair.type)) {
          visitor.visitArray(objectToVisit, objTypePair.type);
        } else if (objTypePair.type == Object.class && isPrimitiveOrString(objectToVisit)) {
          // TODO(Joel): this is only used for deserialization of "primitives"
          // we should rethink this!!!
          visitor.visitPrimitive(objectToVisit);
          visitor.getTarget();
        } else {
          visitor.startVisitingObject(objectToVisit);
          ObjectTypePair currObjTypePair = objTypePair.toMoreSpecificType();
          Class<?> topLevelClass = Types.getRawType(currObjTypePair.type);
          for (Class<?> curr = topLevelClass; curr != null && !curr.equals(Object.class); curr =
              curr.getSuperclass()) {
            if (!curr.isSynthetic()) {
              navigateClassFields(objectToVisit, curr, visitor);
            }
          }
        }
      } finally {
        visitor.end(objTypePair);
      }
    }
  }

  private boolean isPrimitiveOrString(Object objectToVisit) {
    Class<?> realClazz = objectToVisit.getClass();
    return realClazz == Object.class || realClazz == String.class
        || Primitives.unwrap(realClazz).isPrimitive();
  }

  private void navigateClassFields(Object obj, Class<?> clazz, Visitor visitor) {
    Field[] fields = clazz.getDeclaredFields();
    AccessibleObject.setAccessible(fields, true);
    for (Field f : fields) {
      FieldAttributes fieldAttributes = new FieldAttributes(clazz, f);
      if (exclusionStrategy.shouldSkipField(fieldAttributes)
          || exclusionStrategy.shouldSkipClass(fieldAttributes.getDeclaredClass())) {
        continue; // skip
      }
      Type declaredTypeOfField = getTypeInfoForField(f, objTypePair.type);
      boolean visitedWithCustomHandler =
          visitor.visitFieldUsingCustomHandler(fieldAttributes, declaredTypeOfField, obj);
      if (!visitedWithCustomHandler) {
        if (Types.isArray(declaredTypeOfField)) {
          visitor.visitArrayField(fieldAttributes, declaredTypeOfField, obj);
        } else {
          visitor.visitObjectField(fieldAttributes, declaredTypeOfField, obj);
        }
      }
    }
  }


  /**
   * Evaluates the "actual" type for the field.  If the field is a "TypeVariable" or has a
   * "TypeVariable" in a parameterized type then it evaluates the real type.
   *
   * @param f the actual field object to retrieve the type from
   * @param typeDefiningF the type that contains the field {@code f}
   * @return the type information for the field
   */
  public static Type getTypeInfoForField(Field f, Type typeDefiningF) {
    Class<?> rawType = Types.getRawType(typeDefiningF);
    if (!f.getDeclaringClass().isAssignableFrom(rawType)) {
      // this field is unrelated to the type; the user probably omitted type information
      return f.getGenericType();
    }
    return Types.resolve(typeDefiningF, rawType, f.getGenericType());
  }
}
