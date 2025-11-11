import { describe, it, expect } from 'vitest';
import { getPropertyValue } from '../src/getPropertyValue';

describe('getPropertyValue', () => {
  describe('basic functionality', () => {
    it('should retrieve a simple property from an object', () => {
      const obj = { name: 'John' };
      expect(getPropertyValue(obj, 'name')).toBe('John');
    });

    it('should retrieve a nested property using dot notation', () => {
      const obj = { user: { profile: { name: 'Jane' } } };
      expect(getPropertyValue(obj, 'user.profile.name')).toBe('Jane');
    });

    it('should retrieve a property using array path', () => {
      const obj = { user: { profile: { name: 'Jane' } } };
      expect(getPropertyValue(obj, ['user', 'profile', 'name'])).toBe('Jane');
    });

    it('should return default value when property does not exist', () => {
      const obj = { name: 'John' };
      expect(getPropertyValue(obj, 'age', 25)).toBe(25);
    });

    it('should return undefined when property does not exist and no default provided', () => {
      const obj = { name: 'John' };
      expect(getPropertyValue(obj, 'age')).toBeUndefined();
    });
  });

  describe('array access', () => {
    it('should access array element by index', () => {
      const arr = ['a', 'b', 'c'];
      expect(getPropertyValue(arr, '1')).toBe('b');
    });

    it('should access nested array element', () => {
      const obj = { items: ['first', 'second'] };
      expect(getPropertyValue(obj, 'items.0')).toBe('first');
    });

    it('should access object property within array element', () => {
      const arr = [{ name: 'Item1' }, { name: 'Item2' }];
      expect(getPropertyValue(arr, '1.name')).toBe('Item2');
    });

    it('should return default value for out-of-bounds index', () => {
      const arr = ['a', 'b'];
      expect(getPropertyValue(arr, '5', 'default')).toBe('default');
    });

    it('should return default value for negative index', () => {
      const arr = ['a', 'b'];
      expect(getPropertyValue(arr, '-1', 'default')).toBe('default');
    });

    it('should return default value for non-integer string index', () => {
      const arr = ['a', 'b'];
      expect(getPropertyValue(arr, '1.5', 'default')).toBe('default');
    });

    it('should return default value for non-numeric string index', () => {
      const arr = ['a', 'b'];
      expect(getPropertyValue(arr, 'abc', 'default')).toBe('default');
    });
  });

  describe('edge cases with null/undefined', () => {
    it('should return default value when object is null', () => {
      expect(getPropertyValue(null, 'any.path', 'default')).toBe('default');
    });

    it('should return undefined when object is null and no default', () => {
      expect(getPropertyValue(null, 'any.path')).toBeUndefined();
    });

    it('should return default value when object is undefined', () => {
      expect(getPropertyValue(undefined, 'any.path', 'default')).toBe(
        'default',
      );
    });

    it('should return default value when intermediate property is null', () => {
      const obj = { user: null };
      expect(getPropertyValue(obj, 'user.name', 'default')).toBe('default');
    });

    it('should return default value when intermediate property is undefined', () => {
      const obj = { user: undefined };
      expect(getPropertyValue(obj, 'user.name', 'default')).toBe('default');
    });

    it('should return default value when array element is undefined', () => {
      const arr = [undefined, 'b'];
      expect(getPropertyValue(arr, '0', 'default')).toBe('default');
    });
  });

  describe('empty and invalid paths', () => {
    it('should return the object itself when path is empty string', () => {
      const obj = { name: 'John' };
      expect(getPropertyValue(obj, '')).toBe(obj);
    });

    it('should return the object itself when path is empty array', () => {
      const obj = { name: 'John' };
      expect(getPropertyValue(obj, [])).toBe(obj);
    });

    it('should handle path with empty segments', () => {
      const obj = { user: { name: 'John' } };
      expect(getPropertyValue(obj, 'user..name')).toBe('John');
    });

    it('should return default value for invalid path on primitive', () => {
      const primitive = 'string';
      expect(getPropertyValue(primitive, 'length', 'default')).toBe('default');
    });

    it('should return default value when trying to access property on array as object', () => {
      const arr = ['a', 'b'];
      expect(getPropertyValue(arr, 'nonexistent', 'default')).toBe('default');
    });
  });

  describe('mixed object and array structures', () => {
    it('should handle complex nested structure', () => {
      const complex = {
        users: [
          { profile: { name: 'Alice', age: 30 } },
          { profile: { name: 'Bob', age: 25 } },
        ],
        settings: { theme: 'dark' },
      };
      expect(getPropertyValue(complex, 'users.0.profile.name')).toBe('Alice');
      expect(getPropertyValue(complex, 'users.1.profile.age')).toBe(25);
      expect(getPropertyValue(complex, 'settings.theme')).toBe('dark');
    });

    it('should return default value for invalid mixed access', () => {
      const obj = { arr: ['a', 'b'] };
      expect(getPropertyValue(obj, 'arr.name', 'default')).toBe('default');
    });
  });

  describe('type handling', () => {
    it('should handle different value types', () => {
      const obj = {
        string: 'text',
        number: 42,
        boolean: true,
        array: [1, 2, 3],
        object: { nested: 'value' },
        null: null,
        undefined: undefined,
      };
      expect(getPropertyValue(obj, 'string')).toBe('text');
      expect(getPropertyValue(obj, 'number')).toBe(42);
      expect(getPropertyValue(obj, 'boolean')).toBe(true);
      expect(getPropertyValue(obj, 'array')).toEqual([1, 2, 3]);
      expect(getPropertyValue(obj, 'object')).toEqual({ nested: 'value' });
      expect(getPropertyValue(obj, 'null')).toBeUndefined(); // null properties return defaultValue (undefined)
      expect(getPropertyValue(obj, 'undefined')).toBeUndefined();
    });

    it('should return default value when accessing property on non-object', () => {
      const obj = { value: 42 };
      expect(getPropertyValue(obj, 'value.someProp', 'default')).toBe(
        'default',
      );
    });
  });

  describe('deep nesting', () => {
    it('should handle very deep nesting', () => {
      const deep = { a: { b: { c: { d: { e: 'deep value' } } } } };
      expect(getPropertyValue(deep, 'a.b.c.d.e')).toBe('deep value');
    });

    it('should return default value at any level of deep nesting', () => {
      const deep = { a: { b: { c: {} } } };
      expect(getPropertyValue(deep, 'a.b.c.d.e', 'default')).toBe('default');
    });
  });

  describe('special cases', () => {
    it('should handle prototype properties', () => {
      const obj = { name: 'John' };
      // Note: Direct property access includes prototype properties
      expect(typeof getPropertyValue(obj, 'toString')).toBe('function'); // toString is on prototype
    });

    it('should handle zero as valid index', () => {
      const arr = ['first'];
      expect(getPropertyValue(arr, '0')).toBe('first');
    });

    it('should handle large valid index', () => {
      const arr = new Array(1000).fill('item');
      expect(getPropertyValue(arr, '999')).toBe('item');
    });
  });
});
